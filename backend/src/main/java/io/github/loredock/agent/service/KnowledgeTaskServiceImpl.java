package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskSelectedDraftMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskPublicationMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskSelectedDraftEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskPublicationEntity;
import io.github.loredock.agent.model.entity.KnowledgeToolInvocationEntity;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 知识任务会话实现。创建会话、首条系统消息与独立 run 使用同一短事务；模型、Tool 和 Graph
 * 执行不在该事务中。暂停状态只投影既有 run 与真实 PostgreSQL Checkpoint，不维护第二套运行状态机。
 */
@Service
@Slf4j
public class KnowledgeTaskServiceImpl implements KnowledgeTaskService {

    private static final int MAX_CONTINUATION_MESSAGES = 12;
    private static final int MAX_CONTINUATION_CODE_POINTS = 12_000;
    private static final int MAX_CONTINUATION_MESSAGE_CODE_POINTS = 3_000;
    /** 全局知识任务的哨兵项目标识；与 knowledge_draft / agent_run 的 project_identifier 一致。 */
    private static final String GLOBAL_PROJECT_IDENTIFIER = "GLOBAL";
    /** 全局知识任务的哨兵分支名；仅占位，不参与检索范围。 */
    private static final String GLOBAL_BRANCH_NAME = "global";

    private final ProjectService projects;
    private final KnowledgeTaskConversationMapper conversations;
    private final KnowledgeTaskMessageMapper messages;
    private final KnowledgeTaskSelectedDraftMapper selectedDrafts;
    private final AgentRunMapper runs;
    private final PostgresSaver checkpoints;
    private final KnowledgeAgentDefinitionService definitions;
    private final KnowledgeSearchService knowledgeSearch;
    private final AgentService agentService;
    private final KnowledgeCurationRunExecutor executor;
    private final KnowledgeDraftService drafts;
    private final KnowledgeDocumentAccessService documentAccess;
    private final KnowledgeTaskEventService taskEvents;
    private final KnowledgeToolInvocationService toolInvocations;
    private final KnowledgeTaskPublicationMapper publications;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param projects 项目范围契约
     * @param conversations 会话 Mapper
     * @param messages 公开消息 Mapper
     * @param selectedDrafts 会话固定输入草稿 Mapper
     * @param runs 复用的 Agent run Mapper
     * @param checkpoints 框架 PostgreSQL Checkpoint Saver
     * @param definitions 每个新 run 的框架定义加载与预检
     * @param knowledgeSearch 当前活动知识索引版本
     * @param agentService 已提交公开运行事件契约
     * @param executor 框架知识整理运行薄接线
     * @param drafts 当前草稿修订读取契约
     * @param documentAccess 待处理草稿状态与项目范围校验
     * @param clock UTC 时间源
     */
    public KnowledgeTaskServiceImpl(
            ProjectService projects,
            KnowledgeTaskConversationMapper conversations,
            KnowledgeTaskMessageMapper messages,
            KnowledgeTaskSelectedDraftMapper selectedDrafts,
            AgentRunMapper runs,
            PostgresSaver checkpoints,
            KnowledgeAgentDefinitionService definitions,
            KnowledgeSearchService knowledgeSearch,
            AgentService agentService,
            KnowledgeCurationRunExecutor executor,
            KnowledgeDraftService drafts,
            KnowledgeDocumentAccessService documentAccess,
            KnowledgeTaskEventService taskEvents,
            KnowledgeToolInvocationService toolInvocations,
            KnowledgeTaskPublicationMapper publications,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.projects = projects;
        this.conversations = conversations;
        this.messages = messages;
        this.selectedDrafts = selectedDrafts;
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.definitions = definitions;
        this.knowledgeSearch = knowledgeSearch;
        this.agentService = agentService;
        this.executor = executor;
        this.drafts = drafts;
        this.documentAccess = documentAccess;
        this.taskEvents = taskEvents;
        this.toolInvocations = toolInvocations;
        this.publications = publications;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public KnowledgeTask start(StartRequest request) {
        NormalizedStart command = normalize(request);
        ProjectScope scope = scopeOf(command.projectIdentifier());
        String requestHash = hash(String.join("\n", scope.projectIdentifier(), command.triggerType().name(),
                command.triggerReason(), command.targetSkill(), command.goal(),
                command.selectedDraftIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))));
        KnowledgeTaskConversationEntity existing = findByIdempotency(command.operatorId(), command.idempotencyKey());
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            return snapshot(existing);
        }
        List<KnowledgeDocumentAccessService.DocumentContent> inputs;
        try {
            // 全局知识任务只接受通用范围的待处理草稿；项目任务仍按项目范围校验。
            inputs = scope.projectId() == null
                    ? documentAccess.readDraftsGlobal(command.selectedDraftIds())
                    : documentAccess.readDrafts(scope.projectIdentifier(), command.selectedDraftIds());
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_DRAFT_SELECTION_INVALID);
        }
        KnowledgeAgentDefinitionService.LoadedDefinition loaded = definitions.load(command.targetSkill());
        RuntimeDefinition definition = loaded.runtime();
        Instant now = clock.instant();
        KnowledgeTaskConversationEntity pending = KnowledgeTaskConversationEntity.builder()
                .operatorId(command.operatorId()).idempotencyKey(command.idempotencyKey())
                .requestHash(requestHash).projectId(scope.projectId()).projectIdentifier(scope.projectIdentifier())
                .triggerType(command.triggerType().name()).triggerReason(command.triggerReason())
                .targetSkill(command.targetSkill()).goal(command.goal())
                .status(TaskStatus.PROCESSING.name()).createdAt(now).updatedAt(now).build();
        Long conversationId = conversations.insertIfAbsent(pending);
        if (conversationId == null) {
            KnowledgeTaskConversationEntity winner = findByIdempotency(command.operatorId(), command.idempotencyKey());
            if (winner == null) {
                throw new IllegalStateException("知识任务幂等胜者不存在");
            }
            requireSameRequest(winner, requestHash);
            return snapshot(winner);
        }
        pending.setId(conversationId);
        for (int index = 0; index < inputs.size(); index++) {
            KnowledgeDocumentAccessService.DocumentContent input = inputs.get(index);
            selectedDrafts.insert(KnowledgeTaskSelectedDraftEntity.builder()
                    .conversationId(conversationId).documentId(input.documentId())
                    .documentRevision(input.revision()).title(input.title()).directoryPath(input.directory())
                    .markdown(input.markdown()).originalFilename(input.originalFilename())
                    .ordinal(index).curationStatus(CurationStatus.PROCESSING.name()).createdAt(now).build());
        }
        insertMessage(conversationId, null, MessageRole.SYSTEM_TRIGGER, null,
                command.triggerReason() + "\n目标：" + command.goal()
                        + "\n已固定待处理草稿：" + inputs.size() + " 份", now);
        AgentRunEntity run = createRun(pending, scope, command.idempotencyKey(), definition, now);
        taskEvents.append(conversationId, run.getId(), "RUN_UPDATED", run.getId(), now);
        afterCommit(() -> executor.start(run, command.goal(), loaded));
        log.info("knowledge_task started conversationId={} runId={} project={} selectedDraftCount={} "
                        + "triggerType={} skill={}",
                conversationId, run.getId(), scope.projectIdentifier(), inputs.size(),
                command.triggerType(), command.targetSkill());
        return snapshot(pending);
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeTask get(Long conversationId, String operatorId) {
        return snapshot(visibleConversation(conversationId, operatorId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeTaskSummary> list(String projectIdentifier, String operatorId) {
        String project = requireText(projectIdentifier, 64);
        String operator = requireText(operatorId, 128);
        List<KnowledgeTaskConversationEntity> visible = conversations.selectList(
                Wrappers.<KnowledgeTaskConversationEntity>lambdaQuery()
                        .eq(KnowledgeTaskConversationEntity::getProjectIdentifier, project)
                        .eq(KnowledgeTaskConversationEntity::getOperatorId, operator)
                        .orderByDesc(KnowledgeTaskConversationEntity::getUpdatedAt)
                        .orderByDesc(KnowledgeTaskConversationEntity::getId)
                        .last("limit 50"));
        return summarize(visible, operator);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeTaskSummary> listGlobal(String operatorId) {
        String operator = requireText(operatorId, 128);
        // 全局任务由 project_id 为空表达，与项目任务互斥；范围条件直接进入 SQL。
        List<KnowledgeTaskConversationEntity> visible = conversations.selectList(
                Wrappers.<KnowledgeTaskConversationEntity>lambdaQuery()
                        .isNull(KnowledgeTaskConversationEntity::getProjectId)
                        .eq(KnowledgeTaskConversationEntity::getOperatorId, operator)
                        .orderByDesc(KnowledgeTaskConversationEntity::getUpdatedAt)
                        .orderByDesc(KnowledgeTaskConversationEntity::getId)
                        .last("limit 50"));
        return summarize(visible, operator);
    }

    private List<KnowledgeTaskSummary> summarize(
            List<KnowledgeTaskConversationEntity> visible,
            String operator
    ) {
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Long> conversationIds = visible.stream().map(KnowledgeTaskConversationEntity::getId).toList();
        Map<Long, List<AgentRunEntity>> runHistory = runs.selectList(
                        Wrappers.<AgentRunEntity>lambdaQuery()
                                .in(AgentRunEntity::getKnowledgeTaskConversationId, conversationIds)
                                .orderByAsc(AgentRunEntity::getAcceptedAt)
                                .orderByAsc(AgentRunEntity::getId))
                .stream().collect(Collectors.groupingBy(AgentRunEntity::getKnowledgeTaskConversationId));
        Map<Long, Long> inputCounts = selectedDrafts.selectList(
                        Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaQuery()
                                .in(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationIds))
                .stream().collect(Collectors.groupingBy(
                        KnowledgeTaskSelectedDraftEntity::getConversationId, Collectors.counting()));
        return visible.stream().map(conversation -> {
            List<AgentRunEntity> history = runHistory.getOrDefault(conversation.getId(), List.of());
            AgentRunEntity latest = history.isEmpty() ? null : history.getLast();
            int workspaceDocumentCount = latest == null ? 0 : (int) drafts.listWorkspace(
                    new KnowledgeDraftService.AccessContext(
                            operator, conversation.getProjectIdentifier(), conversation.getId(), latest.getId()))
                    .stream().filter(document -> document.currentRevision() > 0).count();
            return new KnowledgeTaskSummary(
                    conversation.getId(), conversation.getProjectIdentifier(),
                    TriggerType.valueOf(conversation.getTriggerType()), conversation.getGoal(),
                    taskStatus(conversation),
                    inputCounts.getOrDefault(conversation.getId(), 0L).intValue(),
                    conversation.getCurrentDraftId(), workspaceDocumentCount, history.size(),
                    latest == null ? null : latest.getId(),
                    latest == null ? null : RunStatus.valueOf(latest.getStatus()),
                    latest == null ? null : latest.getErrorCode(),
                    conversation.getCreatedAt(), conversation.getUpdatedAt());
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeTaskEvent> events(Long conversationId, String operatorId, long after) {
        visibleConversation(conversationId, operatorId);
        return taskEvents.list(conversationId, after, 500).stream()
                .map(value -> new KnowledgeTaskEvent(
                        value.getId(), value.getRunId(), value.getEventType(),
                        value.getSubjectId(), value.getOccurredAt()))
                .toList();
    }

    @Override
    @Transactional
    public KnowledgeTask closeNoChange(CloseRequest request) {
        return close(request, TaskStatus.CLOSED_NO_CHANGE, CurationStatus.CURATED, true);
    }

    @Override
    @Transactional
    public KnowledgeTask abandon(CloseRequest request) {
        return close(request, TaskStatus.ABANDONED, CurationStatus.PENDING, false);
    }

    @Override
    @Transactional
    public TaskPublication publish(PublishTaskRequest request) {
        Long conversationId = requireId(request == null ? null : request.conversationId());
        String operator = requireText(request.operatorId(), 128);
        String key = requireText(request.idempotencyKey(), 128);
        List<KnowledgeDraftService.ReviewedDraft> reviewed = request.reviewedDrafts().stream()
                .sorted(java.util.Comparator.comparing(KnowledgeDraftService.ReviewedDraft::draftId)).toList();
        if (reviewed.isEmpty() || reviewed.size() > 10
                || reviewed.stream().anyMatch(value -> value == null || value.draftId() == null
                        || value.draftId() <= 0 || value.reviewedRevision() <= 0)
                || reviewed.stream().map(KnowledgeDraftService.ReviewedDraft::draftId).distinct().count()
                        != reviewed.size()) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CLOSABLE);
        }
        String requestHash = hash(reviewed.stream()
                .map(value -> value.draftId() + ":" + value.reviewedRevision())
                .collect(Collectors.joining(",")));
        KnowledgeTaskConversationEntity conversation = conversations.selectVisibleForUpdate(conversationId, operator);
        if (conversation == null) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        KnowledgeTaskPublicationEntity replay = publications.selectOne(
                Wrappers.<KnowledgeTaskPublicationEntity>lambdaQuery()
                        .eq(KnowledgeTaskPublicationEntity::getConversationId, conversationId)
                        .eq(KnowledgeTaskPublicationEntity::getIdempotencyKey, key));
        if (replay != null) {
            if (!requestHash.equals(replay.getRequestHash()) || replay.getResultJson() == null) {
                throw new KnowledgeTaskRequestException(
                        KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_IDEMPOTENCY_CONFLICT);
            }
            return publication(replay.getResultJson());
        }
        if (taskStatus(conversation) != TaskStatus.PROCESSING) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CLOSABLE);
        }
        List<AgentRunEntity> history = runEntities(conversationId);
        if (history.isEmpty() || RunStatus.valueOf(history.getLast().getStatus()) != RunStatus.COMPLETED) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CLOSABLE);
        }
        Instant now = clock.instant();
        KnowledgeTaskPublicationEntity publication = KnowledgeTaskPublicationEntity.builder()
                .conversationId(conversationId).operatorId(operator).idempotencyKey(key)
                .requestHash(requestHash).createdAt(now).build();
        publications.insert(publication);
        KnowledgeDraftService.AccessContext context = new KnowledgeDraftService.AccessContext(
                operator, conversation.getProjectIdentifier(), conversationId, history.getLast().getId());
        KnowledgeDraftService.WorkspacePublication published = drafts.publishWorkspace(
                new KnowledgeDraftService.WorkspacePublishRequest(context, reviewed));
        TaskPublication result = new TaskPublication(conversationId, published.documents(), published.publishedAt());
        String resultJson = publication(result);
        publications.update(null, Wrappers.<KnowledgeTaskPublicationEntity>lambdaUpdate()
                .set(KnowledgeTaskPublicationEntity::getResultJson, resultJson)
                .set(KnowledgeTaskPublicationEntity::getCompletedAt, now)
                .eq(KnowledgeTaskPublicationEntity::getId, publication.getId()));
        conversations.update(null, Wrappers.<KnowledgeTaskConversationEntity>lambdaUpdate()
                .set(KnowledgeTaskConversationEntity::getStatus, TaskStatus.PUBLISHED.name())
                .set(KnowledgeTaskConversationEntity::getCloseReason, "管理员原子发布全部工作文档")
                .set(KnowledgeTaskConversationEntity::getClosedAt, now)
                .set(KnowledgeTaskConversationEntity::getUpdatedAt, now)
                .eq(KnowledgeTaskConversationEntity::getId, conversationId));
        selectedDrafts.update(null, Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaUpdate()
                .set(KnowledgeTaskSelectedDraftEntity::getCurationStatus, CurationStatus.CURATED.name())
                .eq(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationId));
        // 原候选草稿内容已被本次发布吸收，归档使其退出待处理草稿池；
        // 归档冲突会随事务整体回滚，不留部分正式发布。
        List<Long> inputDocumentIds = selectedDrafts.selectList(
                        Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaQuery()
                                .eq(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationId))
                .stream().map(KnowledgeTaskSelectedDraftEntity::getDocumentId)
                .distinct().sorted().toList();
        drafts.archiveSelectedInputs(conversationId, inputDocumentIds, operator);
        insertMessage(conversationId, null, MessageRole.SYSTEM_TRIGGER, null,
                "已原子发布 " + published.documents().size() + " 份知识文档", now);
        taskEvents.append(conversationId, null, "TASK_UPDATED", conversationId, now);
        return result;
    }

    @Override
    @Transactional
    public KnowledgeTaskRun requestPause(PauseRequest request) {
        Long runId = requireId(request == null ? null : request.runId());
        String operator = requireText(request.operatorId(), 128);
        Instant now = clock.instant();
        if (runs.requestKnowledgePause(runId, operator, now) != 1) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_PAUSABLE);
        }
        KnowledgeTaskRun result = run(Optional.ofNullable(runs.selectById(runId)).orElseThrow());
        AgentRunEntity persisted = Objects.requireNonNull(runs.selectById(runId));
        afterCommit(() -> executor.requestPause(persisted));
        log.info("knowledge_task pause requested conversationId={} runId={} threadId={} status={}",
                result.conversationId(), result.runId(), result.threadId(), result.status());
        return result;
    }

    @Override
    @Transactional
    public KnowledgeTaskRun stop(StopRequest request) {
        Long runId = requireId(request == null ? null : request.runId());
        String operator = requireText(request.operatorId(), 128);
        AgentRunEntity current = Optional.ofNullable(runs.selectById(runId))
                .filter(value -> operator.equals(value.getOperatorId()))
                .orElseThrow(() -> new KnowledgeTaskRequestException(
                        KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND));
        Instant now = clock.instant();
        if (runs.cancelKnowledge(runId, operator, now) != 1) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_PAUSABLE);
        }
        taskEvents.append(current.getKnowledgeTaskConversationId(), runId, "RUN_UPDATED", runId, now);
        touchConversation(current.getKnowledgeTaskConversationId(), now);
        AgentRunEntity cancelled = Objects.requireNonNull(runs.selectById(runId));
        afterCommit(() -> executor.stop(cancelled));
        return run(cancelled);
    }

    @Override
    @Transactional
    public KnowledgeTaskRun resume(ResumeRequest request) {
        Long runId = requireId(request == null ? null : request.runId());
        String operator = requireText(request.operatorId(), 128);
        String guidance = requireText(request.guidance(), 4000);
        AgentRunEntity current = Optional.ofNullable(runs.selectById(runId))
                .filter(value -> operator.equals(value.getOperatorId()))
                .orElseThrow(() -> new KnowledgeTaskRequestException(
                        KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND));
        if (!RunStatus.WAITING_FOR_USER.name().equals(current.getStatus())) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_WAITING_FOR_USER);
        }
        if (current.getCheckpointSavedAt() == null || current.getThreadId() == null
                || checkpoints.get(RunnableConfig.builder().threadId(current.getThreadId()).build()).isEmpty()) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.AGENT_CHECKPOINT_UNAVAILABLE);
        }
        Instant now = clock.instant();
        KnowledgeAgentDefinitionService.LoadedDefinition loaded = definitions.load(current.getAgentName());
        requireSameDefinition(current, loaded.runtime());
        if (runs.resumeKnowledgeRun(runId, operator, now) != 1) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_WAITING_FOR_USER);
        }
        insertMessage(current.getKnowledgeTaskConversationId(), runId, MessageRole.USER, null, guidance, now);
        touchConversation(current.getKnowledgeTaskConversationId(), now);
        KnowledgeTaskRun result = run(Objects.requireNonNull(runs.selectById(runId)));
        KnowledgeTaskConversationEntity conversation = Objects.requireNonNull(
                conversations.selectById(current.getKnowledgeTaskConversationId()));
        AgentRunEntity resumed = Objects.requireNonNull(runs.selectById(runId));
        afterCommit(() -> executor.resume(resumed, conversation.getGoal(), guidance, loaded));
        log.info("knowledge_task resumed conversationId={} runId={} threadId={} checkpointSavedAt={}",
                result.conversationId(), result.runId(), result.threadId(), result.checkpointSavedAt());
        return result;
    }

    @Override
    @Transactional
    public KnowledgeTaskRun continueTask(ContinueRequest request) {
        Long conversationId = requireId(request == null ? null : request.conversationId());
        String operator = requireText(request.operatorId(), 128);
        String key = requireText(request.idempotencyKey(), 128);
        String guidance = requireText(request.guidance(), 4000);
        KnowledgeTaskConversationEntity conversation = conversations.selectVisibleForUpdate(conversationId, operator);
        if (conversation == null) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        if (taskStatus(conversation) != TaskStatus.PROCESSING) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CONTINUABLE);
        }
        String runKey = runIdempotency(conversationId, key);
        AgentRunEntity replay = findRun(operator, runKey);
        if (replay != null) {
            return run(replay);
        }
        List<AgentRunEntity> history = runEntities(conversationId);
        if (history.isEmpty() || !RunStatus.valueOf(history.getLast().getStatus()).terminal()) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CONTINUABLE);
        }
        Instant now = clock.instant();
        KnowledgeAgentDefinitionService.LoadedDefinition loaded = definitions.load(conversation.getTargetSkill());
        RuntimeDefinition definition = loaded.runtime();
        // 全局任务会话 project_id 为空，续跑沿用哨兵范围，不再解析项目主数据。
        ProjectScope scope = scopeOf(conversation.getProjectId() == null ? null : conversation.getProjectIdentifier());
        String previousDialogue = previousDialogue(conversationId, conversation.getTargetSkill());
        AgentRunEntity created = createRun(conversation, scope, key, definition, now);
        insertMessage(conversationId, created.getId(), MessageRole.USER, null, guidance, now);
        taskEvents.append(conversationId, created.getId(), "RUN_UPDATED", created.getId(), now);
        String continuationPrompt = continuationPrompt(conversation, previousDialogue, guidance);
        afterCommit(() -> executor.start(created, continuationPrompt, loaded));
        touchConversation(conversationId, now);
        log.info("knowledge_task continued conversationId={} previousRunId={} newRunId={} threadId={}",
                conversationId, history.getLast().getId(), created.getId(), created.getThreadId());
        return run(created);
    }

    private String continuationPrompt(
            KnowledgeTaskConversationEntity conversation,
            String previousDialogue,
            String guidance
    ) {
        return "继续已有知识整理会话。\n原始目标：" + conversation.getGoal()
                + "\n以下是之前各轮的用户消息和 Agent 最终回复，不包含过程消息或 Tool 调用。"
                + "历史对话只用于理解指代和已讨论结论，工作文档内容与执行事实必须重新以 Tool 读取结果为准。"
                + "\n<conversation_history>\n" + previousDialogue + "\n</conversation_history>"
                + "\n管理员追加指导：" + guidance
                + "\n先调用 workspace_document_list 查看多文档工作区，再对需要修改的文档调用 draft_read。"
                + "不要假设会话只有一份合并草稿。";
    }

    private String previousDialogue(Long conversationId, String targetSkill) {
        List<String> entries = messages.selectList(
                        Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                                .eq(KnowledgeTaskMessageEntity::getConversationId, conversationId)
                                .orderByAsc(KnowledgeTaskMessageEntity::getCreatedAt)
                                .orderByAsc(KnowledgeTaskMessageEntity::getId))
                .stream()
                .filter(message -> "USER".equals(message.getRole())
                        || ("COORDINATOR_AGENT".equals(message.getRole())
                                && targetSkill.equals(message.getSubjectName())))
                .map(message -> ("USER".equals(message.getRole()) ? "用户：" : "Agent 最终回复：")
                        + boundedHistoryMessage(message.getContent()))
                .toList();
        ArrayDeque<String> selected = new ArrayDeque<>();
        int codePoints = 0;
        for (int index = entries.size() - 1;
                index >= 0 && selected.size() < MAX_CONTINUATION_MESSAGES;
                index--) {
            String entry = entries.get(index);
            int entryCodePoints = entry.codePointCount(0, entry.length());
            if (!selected.isEmpty() && codePoints + entryCodePoints > MAX_CONTINUATION_CODE_POINTS) {
                break;
            }
            selected.addFirst(entry);
            codePoints += entryCodePoints;
        }
        return selected.isEmpty() ? "（没有可用的上一轮对话）" : String.join("\n\n", selected);
    }

    private String boundedHistoryMessage(String value) {
        String text = value == null ? "" : value.strip();
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_CONTINUATION_MESSAGE_CODE_POINTS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_CONTINUATION_MESSAGE_CODE_POINTS)) + "…";
    }

    private AgentRunEntity createRun(
            KnowledgeTaskConversationEntity conversation,
            ProjectScope scope,
            String requestKey,
            RuntimeDefinition definition,
            Instant now
    ) {
        String goalHash = hash(conversation.getGoal());
        AgentRunEntity run = AgentRunEntity.builder()
                .operatorId(conversation.getOperatorId())
                .idempotencyKey(runIdempotency(conversation.getId(), requestKey))
                .requestHash(hash(conversation.getRequestHash() + "\n" + requestKey))
                .taskType("knowledge_curation").questionHash(goalHash)
                .questionLength(conversation.getGoal().codePointCount(0, conversation.getGoal().length()))
                .projectId(scope.projectId()).projectIdentifier(scope.projectIdentifier())
                .branchId(scope.branchId()).branchName(scope.branchName())
                .knowledgeGenerationId(knowledgeSearch.findActiveIndexVersionId().orElse(null))
                .agentName(definition.skillName()).modelName(definition.modelName())
                .configSummary("knowledge-curation-v1")
                .status(RunStatus.ACCEPTED.name()).eventSequence(0L).stepCount(0).modelCallCount(0)
                .toolCallCount(0).retrievalCount(0).trimmedCharacterCount(0)
                .knowledgeTaskConversationId(conversation.getId())
                .threadId("knowledge-task-" + UUID.randomUUID())
                .skillDigest(definition.skillDigest()).agentSpecDigest(definition.agentSpecDigest())
                .toolNames(String.join(",", definition.toolNames()))
                .acceptedAt(now).updatedAt(now).build();
        run.setId(runs.insertKnowledgeRun(run));
        return run;
    }

    private KnowledgeTask snapshot(KnowledgeTaskConversationEntity conversation) {
        List<KnowledgeTaskMessage> visibleMessages = messages.selectList(
                        Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                                .eq(KnowledgeTaskMessageEntity::getConversationId, conversation.getId())
                                .orderByAsc(KnowledgeTaskMessageEntity::getCreatedAt)
                                .orderByAsc(KnowledgeTaskMessageEntity::getId))
                .stream().map(this::message).toList();
        List<KnowledgeTaskRun> visibleRuns = runEntities(conversation.getId()).stream().map(this::run).toList();
        // 只复用 AgentService 已执行操作者/项目授权与字段白名单解析后的公开事件，绝不读取原始 JSON 直出。
        var publicEvents = visibleRuns.stream()
                .flatMap(run -> agentService.listEvents(run.runId(), conversation.getOperatorId(), 0, 200).stream())
                .toList();
        Long currentRevision = null;
        if (conversation.getCurrentDraftId() != null && !visibleRuns.isEmpty()) {
            currentRevision = drafts.read(new KnowledgeDraftService.ReadRequest(
                    new KnowledgeDraftService.AccessContext(
                            conversation.getOperatorId(), conversation.getProjectIdentifier(), conversation.getId(),
                            visibleRuns.getLast().runId()),
                    conversation.getCurrentDraftId(), null)).revision();
        }
        List<SelectedDraft> visibleInputs = selectedDraftEntities(conversation.getId()).stream()
                .map(value -> new SelectedDraft(value.getDocumentId(), value.getTitle(),
                        value.getDirectoryPath(), value.getOriginalFilename(),
                        CurationStatus.valueOf(value.getCurationStatus())))
                .toList();
        KnowledgeDraftService.AccessContext context = visibleRuns.isEmpty() ? null
                : new KnowledgeDraftService.AccessContext(
                        conversation.getOperatorId(), conversation.getProjectIdentifier(), conversation.getId(),
                        visibleRuns.getLast().runId());
        List<KnowledgeDraftService.WorkspaceDocument> workspace = context == null
                ? List.of() : drafts.listWorkspace(context);
        List<KnowledgeDraftService.RunPatchSet> patchSets = context == null ? List.of() : visibleRuns.stream()
                .map(run -> drafts.patchSet(context, run.runId()))
                .toList();
        List<ToolInvocation> tools = toolInvocations.list(conversation.getId()).stream()
                .map(this::toolInvocation).toList();
        return new KnowledgeTask(
                conversation.getId(), conversation.getProjectIdentifier(), TriggerType.valueOf(conversation.getTriggerType()),
                conversation.getTargetSkill(), conversation.getGoal(), taskStatus(conversation), visibleInputs,
                conversation.getCurrentDraftId(), currentRevision,
                workspace, visibleMessages, visibleRuns, publicEvents, tools, patchSets,
                taskEvents.latest(conversation.getId()), conversation.getCreatedAt(), conversation.getUpdatedAt());
    }

    private ToolInvocation toolInvocation(KnowledgeToolInvocationEntity value) {
        return new ToolInvocation(
                value.getId(), value.getRunId(), value.getToolCallId(), defaultInt(value.getSequence()),
                value.getToolName(), value.getAgentNode(), value.getPurpose(), value.getArgumentsText(),
                value.getResultText(), value.getResultSummary(), value.getErrorText(), ToolStatus.valueOf(value.getStatus()),
                Boolean.TRUE.equals(value.getArgumentsTruncated()), Boolean.TRUE.equals(value.getResultTruncated()),
                value.getStartedAt(), value.getFinishedAt(), value.getDurationMillis());
    }

    private KnowledgeTaskMessage message(KnowledgeTaskMessageEntity value) {
        return new KnowledgeTaskMessage(
                value.getId(), value.getRunId(), MessageRole.valueOf(value.getRole()),
                value.getSubjectName(), value.getContent(), value.getCreatedAt());
    }

    private KnowledgeTaskRun run(AgentRunEntity value) {
        RuntimeDefinition runtime = new RuntimeDefinition(
                value.getAgentName(), value.getSkillDigest(), value.getAgentSpecDigest(), value.getModelName(),
                value.getToolNames() == null ? List.of() : List.of(value.getToolNames().split(",")));
        return new KnowledgeTaskRun(
                value.getId(), value.getKnowledgeTaskConversationId(), value.getThreadId(),
                RunStatus.valueOf(value.getStatus()), runtime, value.getCheckpointSavedAt(),
                defaultInt(value.getStepCount()), defaultInt(value.getModelCallCount()),
                defaultInt(value.getToolCallCount()), value.getErrorCode(), value.getAcceptedAt(),
                value.getStartedAt(), value.getFinishedAt());
    }

    private void insertMessage(
            Long conversationId,
            Long runId,
            MessageRole role,
            String subject,
            String content,
            Instant createdAt
    ) {
        KnowledgeTaskMessageEntity message = KnowledgeTaskMessageEntity.builder()
                .conversationId(conversationId).runId(runId).role(role.name()).subjectName(subject)
                .content(content).createdAt(createdAt).build();
        messages.insert(message);
        taskEvents.append(conversationId, runId, "MESSAGE_CREATED", message.getId(), createdAt);
    }

    private KnowledgeTask close(
            CloseRequest request,
            TaskStatus targetStatus,
            CurationStatus selectedStatus,
            boolean requireNoChanges
    ) {
        Long conversationId = requireId(request == null ? null : request.conversationId());
        String operator = requireText(request.operatorId(), 128);
        String reason = requireText(request.reason(), 1000);
        KnowledgeTaskConversationEntity conversation = conversations.selectVisibleForUpdate(conversationId, operator);
        if (conversation == null) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        if (taskStatus(conversation) != TaskStatus.PROCESSING) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CLOSABLE);
        }
        List<AgentRunEntity> history = runEntities(conversationId);
        if (history.isEmpty() || !RunStatus.valueOf(history.getLast().getStatus()).terminal()) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CLOSABLE);
        }
        KnowledgeDraftService.AccessContext context = new KnowledgeDraftService.AccessContext(
                operator, conversation.getProjectIdentifier(), conversationId, history.getLast().getId());
        if (requireNoChanges && drafts.listWorkspace(context).stream()
                .anyMatch(document -> document.currentRevision() > 0)) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_HAS_CHANGES);
        }
        Instant now = clock.instant();
        conversations.update(null, Wrappers.<KnowledgeTaskConversationEntity>lambdaUpdate()
                .set(KnowledgeTaskConversationEntity::getStatus, targetStatus.name())
                .set(KnowledgeTaskConversationEntity::getCloseReason, reason)
                .set(KnowledgeTaskConversationEntity::getClosedAt, now)
                .set(KnowledgeTaskConversationEntity::getUpdatedAt, now)
                .eq(KnowledgeTaskConversationEntity::getId, conversationId));
        selectedDrafts.update(null, Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaUpdate()
                .set(KnowledgeTaskSelectedDraftEntity::getCurationStatus, selectedStatus.name())
                .eq(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationId));
        // 确认无变更与发布一样吸收候选草稿：归档使其退出待处理草稿池，防止草稿列表
        // 残留已整理草稿或再次被选入新任务；归档冲突随事务整体回滚。放弃不归档，
        // 因为放弃时候选恢复 PENDING，草稿必须留在池中等待重新整理。
        if (targetStatus == TaskStatus.CLOSED_NO_CHANGE) {
            List<Long> inputDocumentIds = selectedDrafts.selectList(
                            Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaQuery()
                                    .eq(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationId))
                    .stream().map(KnowledgeTaskSelectedDraftEntity::getDocumentId)
                    .distinct().sorted().toList();
            drafts.archiveSelectedInputs(conversationId, inputDocumentIds, operator);
        }
        insertMessage(conversationId, null, MessageRole.SYSTEM_TRIGGER, null,
                targetStatus == TaskStatus.ABANDONED ? "任务已放弃：" + reason : "管理员确认无需变更：" + reason, now);
        taskEvents.append(conversationId, null, "TASK_UPDATED", conversationId, now);
        conversation.setStatus(targetStatus.name());
        conversation.setCloseReason(reason);
        conversation.setClosedAt(now);
        conversation.setUpdatedAt(now);
        return snapshot(conversation);
    }

    private TaskStatus taskStatus(KnowledgeTaskConversationEntity conversation) {
        return conversation.getStatus() == null
                ? TaskStatus.PROCESSING : TaskStatus.valueOf(conversation.getStatus());
    }

    private String publication(TaskPublication value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识任务发布结果无法序列化", exception);
        }
    }

    private TaskPublication publication(String value) {
        try {
            return objectMapper.readValue(value, TaskPublication.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识任务发布结果无法读取", exception);
        }
    }

    private void touchConversation(Long conversationId, Instant updatedAt) {
        conversations.update(null, Wrappers.<KnowledgeTaskConversationEntity>lambdaUpdate()
                .set(KnowledgeTaskConversationEntity::getUpdatedAt, updatedAt)
                .eq(KnowledgeTaskConversationEntity::getId, conversationId));
    }

    private KnowledgeTaskConversationEntity visibleConversation(Long conversationId, String operatorId) {
        if (conversationId == null || operatorId == null || operatorId.isBlank()) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        return Optional.ofNullable(conversations.selectOne(
                        Wrappers.<KnowledgeTaskConversationEntity>lambdaQuery()
                                .eq(KnowledgeTaskConversationEntity::getId, conversationId)
                                .eq(KnowledgeTaskConversationEntity::getOperatorId, operatorId)))
                .orElseThrow(() -> new KnowledgeTaskRequestException(
                        KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND));
    }

    private KnowledgeTaskConversationEntity findByIdempotency(String operatorId, String key) {
        return conversations.selectOne(Wrappers.<KnowledgeTaskConversationEntity>lambdaQuery()
                .eq(KnowledgeTaskConversationEntity::getOperatorId, operatorId)
                .eq(KnowledgeTaskConversationEntity::getIdempotencyKey, key));
    }

    private AgentRunEntity findRun(String operatorId, String key) {
        return runs.selectOne(Wrappers.<AgentRunEntity>lambdaQuery()
                .eq(AgentRunEntity::getOperatorId, operatorId)
                .eq(AgentRunEntity::getIdempotencyKey, key));
    }

    private List<AgentRunEntity> runEntities(Long conversationId) {
        return runs.selectList(Wrappers.<AgentRunEntity>lambdaQuery()
                .eq(AgentRunEntity::getKnowledgeTaskConversationId, conversationId)
                .orderByAsc(AgentRunEntity::getAcceptedAt).orderByAsc(AgentRunEntity::getId));
    }

    private List<KnowledgeTaskSelectedDraftEntity> selectedDraftEntities(Long conversationId) {
        return selectedDrafts.selectList(Wrappers.<KnowledgeTaskSelectedDraftEntity>lambdaQuery()
                .eq(KnowledgeTaskSelectedDraftEntity::getConversationId, conversationId)
                .orderByAsc(KnowledgeTaskSelectedDraftEntity::getOrdinal));
    }

    private void requireSameRequest(KnowledgeTaskConversationEntity existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_IDEMPOTENCY_CONFLICT);
        }
    }

    private NormalizedStart normalize(StartRequest request) {
        Objects.requireNonNull(request, "request");
        return new NormalizedStart(
                requireText(request.idempotencyKey(), 128), requireText(request.operatorId(), 128),
                optionalText(request.projectIdentifier(), 64), requireDraftIds(request.selectedDraftIds()),
                Objects.requireNonNull(request.triggerType(), "triggerType"),
                requireText(request.triggerReason(), 1000), requireText(request.targetSkill(), 64),
                requireText(request.goal(), 2000));
    }

    /**
     * 解析任务范围：项目标识为空表示全局知识任务（project_id 为空 + 哨兵标识），
     * 不解析项目主数据；项目任务仍走启用项目解析。
     */
    private ProjectScope scopeOf(String projectIdentifier) {
        if (projectIdentifier == null) {
            return new ProjectScope(null, GLOBAL_PROJECT_IDENTIFIER, null, true, null, GLOBAL_BRANCH_NAME);
        }
        return projects.resolveEnabledScope(projectIdentifier, null);
    }

    private String optionalText(String value, int maximumCodePoints) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException("知识任务文本参数无效");
        }
        return normalized;
    }

    private String requireText(String value, int maximumCodePoints) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException("知识任务文本参数无效");
        }
        return normalized;
    }

    private Long requireId(Long value) {
        if (value == null || value <= 0) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND);
        }
        return value;
    }

    private List<Long> requireDraftIds(List<Long> values) {
        List<Long> ids = values == null ? List.of() : List.copyOf(values);
        if (ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("待处理草稿选择无效");
        }
        return ids;
    }

    private String runIdempotency(Long conversationId, String key) {
        return "knowledge-task:" + hash(conversationId + "\n" + key);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireSameDefinition(AgentRunEntity run, RuntimeDefinition current) {
        if (!Objects.equals(run.getSkillDigest(), current.skillDigest())
                || !Objects.equals(run.getAgentSpecDigest(), current.agentSpecDigest())
                || !Objects.equals(run.getToolNames(), String.join(",", current.toolNames()))) {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.AGENT_DEFINITION_INVALID);
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private record NormalizedStart(
            String idempotencyKey,
            String operatorId,
            String projectIdentifier,
            List<Long> selectedDraftIds,
            TriggerType triggerType,
            String triggerReason,
            String targetSkill,
            String goal
    ) {
    }
}
