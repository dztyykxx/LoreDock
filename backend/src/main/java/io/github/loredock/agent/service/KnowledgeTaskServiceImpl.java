package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskSelectedDraftMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskSelectedDraftEntity;
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
        this.clock = clock;
    }

    @Override
    @Transactional
    public KnowledgeTask start(StartRequest request) {
        NormalizedStart command = normalize(request);
        ProjectScope scope = projects.resolveEnabledScope(command.projectIdentifier(), null);
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
            inputs = documentAccess.readDrafts(scope.projectIdentifier(), command.selectedDraftIds());
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
                .targetSkill(command.targetSkill()).goal(command.goal()).createdAt(now).updatedAt(now).build();
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
                    .ordinal(index).createdAt(now).build());
        }
        insertMessage(conversationId, null, MessageRole.SYSTEM_TRIGGER, null,
                command.triggerReason() + "\n目标：" + command.goal()
                        + "\n已固定待处理草稿：" + inputs.size() + " 份", now);
        AgentRunEntity run = createRun(pending, scope, command.idempotencyKey(), definition, now);
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
            AgentRunEntity latest = history.getLast();
            return new KnowledgeTaskSummary(
                    conversation.getId(), conversation.getProjectIdentifier(),
                    TriggerType.valueOf(conversation.getTriggerType()), conversation.getGoal(),
                    inputCounts.getOrDefault(conversation.getId(), 0L).intValue(),
                    conversation.getCurrentDraftId(), history.size(), latest.getId(),
                    RunStatus.valueOf(latest.getStatus()), latest.getErrorCode(),
                    conversation.getCreatedAt(), conversation.getUpdatedAt());
        }).toList();
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
        String runKey = runIdempotency(conversationId, key);
        AgentRunEntity replay = findRun(operator, runKey);
        if (replay != null) {
            return run(replay);
        }
        List<AgentRunEntity> history = runEntities(conversationId);
        if (history.isEmpty() || !List.of(RunStatus.COMPLETED.name(), RunStatus.FAILED.name())
                .contains(history.getLast().getStatus())) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_CONTINUABLE);
        }
        Instant now = clock.instant();
        KnowledgeAgentDefinitionService.LoadedDefinition loaded = definitions.load(conversation.getTargetSkill());
        RuntimeDefinition definition = loaded.runtime();
        insertMessage(conversationId, null, MessageRole.USER, null, guidance, now);
        ProjectScope scope = projects.resolveEnabledScope(conversation.getProjectIdentifier(), null);
        AgentRunEntity created = createRun(conversation, scope, key, definition, now);
        String continuationPrompt = continuationPrompt(conversation, guidance);
        afterCommit(() -> executor.start(created, continuationPrompt, loaded));
        // 用户消息在 run 创建前落库以保持入口原子性，随后回填本轮 run 便于审计。
        messages.update(null, Wrappers.<KnowledgeTaskMessageEntity>lambdaUpdate()
                .set(KnowledgeTaskMessageEntity::getRunId, created.getId())
                .eq(KnowledgeTaskMessageEntity::getConversationId, conversationId)
                .isNull(KnowledgeTaskMessageEntity::getRunId)
                .eq(KnowledgeTaskMessageEntity::getRole, MessageRole.USER.name())
                .eq(KnowledgeTaskMessageEntity::getCreatedAt, now));
        touchConversation(conversationId, now);
        log.info("knowledge_task continued conversationId={} previousRunId={} newRunId={} threadId={}",
                conversationId, history.getLast().getId(), created.getId(), created.getThreadId());
        return run(created);
    }

    private String continuationPrompt(KnowledgeTaskConversationEntity conversation, String guidance) {
        String draftContext = conversation.getCurrentDraftId() == null
                ? "当前会话尚未创建合并草稿；完成核对后只创建一份。"
                : "继续修改当前合并草稿，draftId=" + conversation.getCurrentDraftId()
                        + "；先调用 draft_read 取得最新修订，不要创建第二份草稿。";
        return "继续已有知识整理会话。\n原始目标：" + conversation.getGoal()
                + "\n管理员追加指导：" + guidance + "\n" + draftContext;
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
                        value.getDirectoryPath(), value.getOriginalFilename()))
                .toList();
        return new KnowledgeTask(
                conversation.getId(), conversation.getProjectIdentifier(), TriggerType.valueOf(conversation.getTriggerType()),
                conversation.getTargetSkill(), conversation.getGoal(), visibleInputs,
                conversation.getCurrentDraftId(), currentRevision,
                visibleMessages, visibleRuns, publicEvents, conversation.getCreatedAt(), conversation.getUpdatedAt());
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
                requireText(request.projectIdentifier(), 64), requireDraftIds(request.selectedDraftIds()),
                Objects.requireNonNull(request.triggerType(), "triggerType"),
                requireText(request.triggerReason(), 1000), requireText(request.targetSkill(), 64),
                requireText(request.goal(), 2000));
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
