package io.github.loredock.qa.service;

import io.github.loredock.agent.api.AgentRequestException;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentRunNotFoundException;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.api.QaConversationBusyException;
import io.github.loredock.qa.api.QaConversationNotFoundException;
import io.github.loredock.qa.api.QaQuestionNotFoundException;
import io.github.loredock.qa.api.QaQuestionPage;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.converter.WebQaCursorCodec;
import io.github.loredock.qa.model.WebQaIdempotencyKey;
import io.github.loredock.qa.model.WebQaQuestionText;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaConversationRecord;
import io.github.loredock.qa.model.result.WebQaGlobalConversationRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.result.WebQaStreamTarget;
import io.github.loredock.qa.model.snapshot.WebQaCursor;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QA 统一契约实现：短事务创建问答与 Agent 受理事实，查询时按操作者和项目复核范围，
 * 终态助手消息以 Agent 已提交事实自愈投影。
 */
@Service
@Slf4j
public class QaServiceImpl implements QaService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CONVERSATION_ROUNDS = 100;
    private static final int MAX_CONTEXT_ROUNDS = 8;
    private static final int MAX_CONTEXT_CODE_POINTS = 8000;
    private static final int MAX_TITLE_CODE_POINTS = 200;
    /** 全局会话的哨兵项目标识；与 AgentServiceImpl 全局运行保持一致。 */
    private static final String GLOBAL_SCOPE_IDENTIFIER = "GLOBAL";
    /** 全局会话的哨兵分支名；仅占位，不参与检索范围。 */
    private static final String GLOBAL_SCOPE_BRANCH = "global";
    private final ProjectService projects;
    private final AgentService agents;
    private final WebQaConversationDataService conversations;
    private final WebQaQuestionDataService questions;
    private final WebQaMessageDataService messages;
    private final DefaultWebQaAssistantMessageMaterializer materializer;
    private final Clock timeProvider;

    /**
     * @param projects 启用项目和明确分支解析
     * @param agents Agent 运行受理与安全查询契约
     * @param conversations QA 会话归属和最近活动仓储
     * @param questions 问答身份仓储
     * @param messages 问答消息仓储
     * @param materializer 终态助手消息自愈投影
     * @param timeProvider UTC 时间源
     */
    public QaServiceImpl(
            ProjectService projects,
            AgentService agents,
            WebQaConversationDataService conversations,
            WebQaQuestionDataService questions,
            WebQaMessageDataService messages,
            DefaultWebQaAssistantMessageMaterializer materializer,
            Clock timeProvider
    ) {
        this.projects = projects;
        this.agents = agents;
        this.conversations = conversations;
        this.questions = questions;
        this.messages = messages;
        this.materializer = materializer;
        this.timeProvider = timeProvider;
    }

    @Override
    @Transactional
    public QaQuestion create(CreateRequest request) {
        return toApi(createSnapshot(request));
    }

    WebQaQuestionSnapshot createSnapshot(CreateRequest request) {
        NormalizedCreate command = normalize(request);
        ProjectScope project = projects.resolveEnabledScope(command.projectIdentifier(), command.branch());
        String requestHash = hash(project.projectIdentifier() + "\n" + project.branchName()
                + "\n" + (command.conversationId() == null ? "NEW" : command.conversationId())
                + "\n" + command.question().value());
        var existing = questions.findByOperatorAndIdempotencyKey(
                command.operatorId(), command.idempotencyKey().value());
        if (existing.isPresent()) {
            requireSameRequest(existing.get(), requestHash);
            WebQaQuestionSnapshot reused = snapshot(
                    existing.get(), agents.get(existing.get().runId(), command.operatorId()));
            log.info("web_qa create reused traceId={} questionId={} runId={} project={} branch={} status={} "
                            + "questionLength={} questionDigest={}",
                    traceId(existing.get().id()), existing.get().id(), existing.get().runId(),
                    existing.get().projectIdentifier(), existing.get().branch(), reused.run().status(),
                    command.question().codePointLength(), shortHash(command.question().value()));
            return reused;
        }

        Instant createdAt = timeProvider.instant();
        WebQaConversationRecord conversation;
        boolean createdConversation = command.conversationId() == null;
        if (createdConversation) {
            conversation = conversations.insert(new WebQaConversationRecord(
                    null, command.operatorId(), project.projectId(), project.projectIdentifier(),
                    truncate(command.question().value(), MAX_TITLE_CODE_POINTS), createdAt, createdAt, createdAt));
        } else {
            conversation = conversations.lockVisible(command.conversationId(), command.operatorId(), project.projectId())
                    .orElseThrow(QaConversationNotFoundException::new);
            if (questions.hasActiveRound(conversation.id())) {
                throw new QaConversationBusyException();
            }
        }
        List<AgentService.ConversationMessage> history = completedHistory(conversation.id(), command.operatorId());
        AgentRun run = agents.start(new AgentService.StartRequest(
                agentIdempotencyKey(command.idempotencyKey().value()), command.operatorId(), command.operatorRole(),
                project.projectIdentifier(), project.branchName(), command.question().value(), history));
        return persistRound(command.operatorId(), command.idempotencyKey(), requestHash, conversation.id(),
                createdConversation, run, command.question(), createdAt);
    }

    @Override
    @Transactional
    public QaQuestion createGlobal(GlobalCreateRequest request) {
        return toApi(createGlobalSnapshot(request));
    }

    WebQaQuestionSnapshot createGlobalSnapshot(GlobalCreateRequest request) {
        GlobalNormalizedCreate command = normalizeGlobal(request);
        String requestHash = hash(GLOBAL_SCOPE_IDENTIFIER + "\n" + GLOBAL_SCOPE_BRANCH + "\n"
                + (command.conversationId() == null ? "NEW" : command.conversationId())
                + "\n" + command.question().value());
        var existing = questions.findByOperatorAndIdempotencyKey(
                command.operatorId(), command.idempotencyKey().value());
        if (existing.isPresent()) {
            requireSameRequest(existing.get(), requestHash);
            WebQaQuestionSnapshot reused = snapshot(
                    existing.get(), agents.get(existing.get().runId(), command.operatorId()));
            log.info("web_qa createGlobal reused traceId={} questionId={} runId={} scope={} status={} "
                            + "questionLength={} questionDigest={}",
                    traceId(existing.get().id()), existing.get().id(), existing.get().runId(),
                    existing.get().projectIdentifier(), reused.run().status(),
                    command.question().codePointLength(), shortHash(command.question().value()));
            return reused;
        }
        Instant createdAt = timeProvider.instant();
        WebQaConversationRecord conversation;
        boolean createdConversation = command.conversationId() == null;
        if (createdConversation) {
            // 全局会话不解析项目主数据：project_id 为空，项目标识写哨兵，供可见性与列表区分范围。
            conversation = conversations.insert(new WebQaConversationRecord(
                    null, command.operatorId(), null, GLOBAL_SCOPE_IDENTIFIER,
                    truncate(command.question().value(), MAX_TITLE_CODE_POINTS), createdAt, createdAt, createdAt));
        } else {
            conversation = conversations.lockVisible(command.conversationId(), command.operatorId(), null)
                    .orElseThrow(QaConversationNotFoundException::new);
            if (questions.hasActiveRound(conversation.id())) {
                throw new QaConversationBusyException();
            }
        }
        List<AgentService.ConversationMessage> history = completedHistory(conversation.id(), command.operatorId());
        AgentRun run = agents.startGlobal(new AgentService.GlobalStartRequest(
                agentIdempotencyKey(command.idempotencyKey().value()), command.operatorId(), command.operatorRole(),
                command.question().value(), history));
        return persistRound(command.operatorId(), command.idempotencyKey(), requestHash, conversation.id(),
                createdConversation, run, command.question(), createdAt);
    }

    /**
     * 落库新轮次并在幂等竞争时复读数据库胜者；项目问答与全局问答共用同一组并发保护规则。
     */
    private WebQaQuestionSnapshot persistRound(
            String operatorId,
            WebQaIdempotencyKey idempotencyKey,
            String requestHash,
            Long conversationId,
            boolean createdConversation,
            AgentRun run,
            WebQaQuestionText question,
            Instant createdAt
    ) {
        WebQaQuestionRecord questionRecord = new WebQaQuestionRecord(
                null, conversationId, operatorId, idempotencyKey.value(), requestHash,
                run.scope().projectId(), run.scope().projectIdentifier(), run.scope().branchId(), run.scope().branch(),
                run.runId(), createdAt);
        var insertedQuestionId = questions.insertIfAbsent(questionRecord);
        if (insertedQuestionId.isEmpty()) {
            // 并发请求只能复读数据库胜者；不得以本事务内临时对象覆盖已提交事实。
            if (createdConversation) {
                // 首轮竞争输家的会话没有任何问题，必须在同一事务内清理，避免最近会话出现空记录。
                conversations.deleteEmpty(conversationId);
            }
            WebQaQuestionRecord raced = questions.findByOperatorAndIdempotencyKey(operatorId, idempotencyKey.value())
                    .orElseThrow(() -> new IllegalStateException("web QA idempotent winner missing"));
            requireSameRequest(raced, requestHash);
            return snapshot(raced, agents.get(raced.runId(), operatorId));
        }
        WebQaQuestionRecord persisted = new WebQaQuestionRecord(
                insertedQuestionId.orElseThrow(), questionRecord.conversationId(), questionRecord.operatorId(),
                questionRecord.idempotencyKey(), questionRecord.requestHash(), questionRecord.projectId(),
                questionRecord.projectIdentifier(), questionRecord.branchId(), questionRecord.branch(),
                questionRecord.runId(), questionRecord.createdAt());
        WebQaMessageRecord pendingUserMessage = new WebQaMessageRecord(
                null, persisted.id(), WebQaMessageRole.USER, question.value(), null, null, createdAt);
        var insertedMessageId = messages.insertIfAbsent(pendingUserMessage);
        if (insertedMessageId.isEmpty()) {
            throw new IllegalStateException("new web QA user message role already exists");
        }
        WebQaMessageRecord userMessage = new WebQaMessageRecord(
                insertedMessageId.orElseThrow(), pendingUserMessage.questionId(), pendingUserMessage.role(),
                pendingUserMessage.content(), pendingUserMessage.resultType(), pendingUserMessage.refusalReason(),
                pendingUserMessage.createdAt());
        conversations.updateActivity(conversationId, createdAt);
        log.info("web_qa create completed traceId={} questionId={} runId={} project={} branch={} status={} "
                        + "questionLength={} questionDigest={}",
                traceId(persisted.id()), persisted.id(), run.runId(), persisted.projectIdentifier(),
                persisted.branch(), run.status(), question.codePointLength(), shortHash(question.value()));
        return new WebQaQuestionSnapshot(persisted, run, trustState(run), List.of(userMessage));
    }

    @Override
    public QaQuestionPage history(HistoryQuery query) {
        requireIdentity(query.operatorId(), query.projectIdentifier());
        if (query.limit() < 1 || query.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA page size out of range");
        }
        ProjectScope project = enabledProject(query.projectIdentifier());
        return historyPage(questionHistory(query.operatorId(), project.projectId(), query.cursor(), query.limit()),
                query.limit(), project.projectIdentifier());
    }

    @Override
    public QaQuestionPage historyGlobal(GlobalHistoryQuery query) {
        if (query.operatorId() == null || query.operatorId().isBlank()) {
            throw new QaQuestionNotFoundException();
        }
        if (query.limit() < 1 || query.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA page size out of range");
        }
        List<WebQaQuestionRecord> records = questionHistory(query.operatorId(), null, query.cursor(), query.limit());
        return historyPage(records, query.limit(), GLOBAL_SCOPE_IDENTIFIER);
    }

    /** @return 当前操作者与范围的问答历史分页；projectId 为空时只读全局轮次 */
    private List<WebQaQuestionRecord> questionHistory(String operatorId, Long projectId, String cursor, int limit) {
        WebQaCursor after = cursor == null || cursor.isBlank() ? null : WebQaCursorCodec.decode(cursor);
        return questions.findHistory(operatorId, projectId, after, limit + 1);
    }

    private QaQuestionPage historyPage(List<WebQaQuestionRecord> records, int limit, String scopeLabel) {
        boolean hasMore = records.size() > limit;
        List<WebQaQuestionRecord> visible = hasMore ? records.subList(0, limit) : records;
        List<WebQaQuestionSnapshot> snapshots = new ArrayList<>(visible.size());
        for (WebQaQuestionRecord question : visible) {
            snapshots.add(snapshot(question, agents.get(question.runId(), question.operatorId())));
        }
        String nextCursor = hasMore && !visible.isEmpty()
                ? WebQaCursorCodec.encode(new WebQaCursor(visible.getLast().createdAt(), visible.getLast().id()))
                : null;
        log.info("web_qa history queried traceId={} scope={} resultCount={} hasMore={}",
                traceId(), scopeLabel, snapshots.size(), hasMore);
        return new QaQuestionPage(snapshots.stream().map(this::toApi).toList(), nextCursor);
    }

    @Override
    public QaQuestion detail(DetailQuery query) {
        WebQaQuestionSnapshot snapshot = detailSnapshot(query);
        return toApi(snapshot);
    }

    @Override
    public ConversationPage conversations(ConversationHistoryQuery query) {
        requireIdentity(query.operatorId(), query.projectIdentifier());
        if (query.limit() < 1 || query.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA conversation page size out of range");
        }
        ProjectScope project = enabledProject(query.projectIdentifier());
        WebQaCursor after = query.cursor() == null || query.cursor().isBlank()
                ? null : WebQaCursorCodec.decode(query.cursor());
        List<WebQaConversationRecord> records = conversations.findHistory(
                query.operatorId(), project.projectId(), after, query.limit() + 1);
        boolean hasMore = records.size() > query.limit();
        List<WebQaConversationRecord> visible = hasMore ? records.subList(0, query.limit()) : records;
        List<ConversationSummary> items = visible.stream()
                .map(value -> summary(value, null, query.operatorId())).toList();
        String nextCursor = hasMore && !visible.isEmpty()
                ? WebQaCursorCodec.encode(new WebQaCursor(
                        visible.getLast().lastQuestionAt(), visible.getLast().id()))
                : null;
        return new ConversationPage(items, nextCursor);
    }

    @Override
    public ConversationPage conversationsGlobal(GlobalConversationHistoryQuery query) {
        if (query.operatorId() == null || query.operatorId().isBlank()) {
            throw new QaConversationNotFoundException();
        }
        if (query.limit() < 1 || query.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA conversation page size out of range");
        }
        WebQaCursor after = query.cursor() == null || query.cursor().isBlank()
                ? null : WebQaCursorCodec.decode(query.cursor());
        List<WebQaGlobalConversationRecord> records = conversations.findGlobalHistory(
                query.operatorId(), after, query.limit() + 1);
        boolean hasMore = records.size() > query.limit();
        List<WebQaGlobalConversationRecord> visible = hasMore ? records.subList(0, query.limit()) : records;
        List<ConversationSummary> items = visible.stream()
                .map(value -> summary(fromRow(value), value.projectName(), query.operatorId())).toList();
        String nextCursor = hasMore && !visible.isEmpty()
                ? WebQaCursorCodec.encode(new WebQaCursor(
                        visible.getLast().lastQuestionAt(), visible.getLast().id()))
                : null;
        log.info("web_qa global conversation history queried traceId={} resultCount={} hasMore={}",
                traceId(), items.size(), hasMore);
        return new ConversationPage(items, nextCursor);
    }

    @Override
    public Conversation conversationGlobal(GlobalConversationDetailQuery query) {
        if (query.operatorId() == null || query.operatorId().isBlank()) {
            throw new QaConversationNotFoundException();
        }
        if (query.conversationId() == null) {
            throw new QaConversationNotFoundException();
        }
        WebQaConversationRecord conversation = conversations.findVisible(
                        query.conversationId(), query.operatorId(), null)
                .orElseThrow(QaConversationNotFoundException::new);
        return conversationWithRounds(conversation, query.operatorId());
    }

    @Override
    public QaQuestion detailGlobal(GlobalDetailQuery query) {
        if (query.operatorId() == null || query.operatorId().isBlank()) {
            throw new QaQuestionNotFoundException();
        }
        if (query.questionId() == null) {
            throw new QaQuestionNotFoundException();
        }
        // 全局详情只允许访问 project_id 为空的全局轮次，项目轮次由项目端点提供。
        WebQaQuestionRecord question = questions.findVisibleById(
                        query.operatorId(), null, query.questionId())
                .orElseThrow(QaQuestionNotFoundException::new);
        try {
            return toApi(snapshot(question, agents.get(question.runId(), query.operatorId())));
        } catch (AgentRunNotFoundException exception) {
            throw new QaQuestionNotFoundException();
        }
    }

    private Conversation conversationWithRounds(WebQaConversationRecord conversation, String operatorId) {
        List<WebQaQuestionRecord> records = questions.findByConversation(conversation.id(), MAX_CONVERSATION_ROUNDS + 1);
        if (records.size() > MAX_CONVERSATION_ROUNDS) {
            records = records.subList(0, MAX_CONVERSATION_ROUNDS);
        }
        List<QaQuestion> rounds = records.stream()
                .map(value -> toApi(snapshot(value, agents.get(value.runId(), operatorId)))).toList();
        return new Conversation(summary(conversation, null, operatorId), rounds);
    }

    @Override
    public Conversation conversation(ConversationDetailQuery query) {
        requireIdentity(query.operatorId(), query.projectIdentifier());
        if (query.conversationId() == null) {
            throw new QaConversationNotFoundException();
        }
        ProjectScope project = enabledProject(query.projectIdentifier());
        WebQaConversationRecord conversation = conversations.findVisible(
                        query.conversationId(), query.operatorId(), project.projectId())
                .orElseThrow(QaConversationNotFoundException::new);
        return conversationWithRounds(conversation, query.operatorId());
    }

    WebQaQuestionSnapshot detailSnapshot(DetailQuery query) {
        WebQaStreamTarget target = authorizeInternal(query);
        WebQaQuestionSnapshot result = snapshot(target.question(), target.run());
        log.info("web_qa detail queried traceId={} questionId={} runId={} project={} branch={} status={}",
                traceId(), target.question().id(), target.run().runId(), target.question().projectIdentifier(),
                target.question().branch(), target.run().status());
        return result;
    }

    WebQaStreamTarget authorizeInternal(DetailQuery query) {
        requireIdentity(query.operatorId(), query.projectIdentifier());
        if (query.questionId() == null) {
            throw new QaQuestionNotFoundException();
        }
        // 全局 SSE 以哨兵标识进入：不解析项目主数据，只复核操作者与 project_id 为空的全局轮次；
        // 项目问答仍走启用项目解析（防止"GLOBAL"被当作真实项目）。
        Long projectId = GLOBAL_SCOPE_IDENTIFIER.equals(query.projectIdentifier())
                ? null : enabledProject(query.projectIdentifier()).projectId();
        WebQaQuestionRecord question = questions.findVisibleById(
                        query.operatorId(), projectId, query.questionId())
                .orElseThrow(QaQuestionNotFoundException::new);
        try {
            return new WebQaStreamTarget(question, agents.get(question.runId(), query.operatorId()));
        } catch (AgentRunNotFoundException exception) {
            throw new QaQuestionNotFoundException();
        }
    }

    private WebQaQuestionSnapshot snapshot(WebQaQuestionRecord question, AgentRun run) {
        try {
            materializer.materialize(question, run);
        } catch (RuntimeException exception) {
            // Agent 终态是事实来源；投影失败不能覆盖正确终态，下次读取会继续尝试。
            log.warn("web_qa assistant projection deferred traceId={} questionId={} runId={} "
                            + "errorCode=WEB_QA_MESSAGE_PROJECTION_FAILED",
                    traceId(), question.id(), run.runId());
        }
        List<WebQaMessageRecord> values = messages.findByQuestionId(question.id());
        return new WebQaQuestionSnapshot(question, run, trustState(run), values);
    }

    private ProjectScope enabledProject(String identifier) {
        try {
            return projects.resolveEnabledScope(identifier, null);
        } catch (RuntimeException exception) {
            throw new QaQuestionNotFoundException();
        }
    }

    private void requireIdentity(String operatorId, String projectIdentifier) {
        if (operatorId == null || operatorId.isBlank()
                || projectIdentifier == null || projectIdentifier.isBlank()) {
            throw new QaQuestionNotFoundException();
        }
    }

    private QaQuestion toApi(WebQaQuestionSnapshot snapshot) {
        AgentRun run = snapshot.run();
        AgentRun.Scope scope = run.scope();
        return new QaQuestion(
                snapshot.question().id(), snapshot.question().conversationId(), run.runId(),
                new QaQuestion.Scope(scope.projectId(), scope.projectIdentifier(), scope.branchId(), scope.branch(),
                        scope.commit(), scope.hasCodeSnapshot()), snapshot.question().createdAt(),
                QaQuestion.Status.valueOf(run.status().name()),
                enumValue(QaQuestion.ResultType.class, run.resultType()),
                QaQuestion.TrustState.valueOf(snapshot.trustState().name()),
                enumValue(QaQuestion.AnswerBasis.class, run.answerBasis()),
                enumValue(QaQuestion.RefusalReason.class, run.refusalReason()),
                enumValue(QaQuestion.ErrorCode.class, run.errorCode()), run.resultText(),
                run.stepCount(), run.modelCallCount(), run.finishedAt(),
                snapshot.messages().stream().map(message -> new QaQuestion.Message(
                        message.id(), QaQuestion.MessageRole.valueOf(message.role().name()), message.content(),
                        enumValue(QaQuestion.ResultType.class, message.resultType()),
                        enumValue(QaQuestion.RefusalReason.class, message.refusalReason()),
                        message.createdAt())).toList(),
                run.citations().stream().map(citation -> {
                    AgentRun.SourceMetadata metadata = citation.sourceMetadata();
                    return new QaQuestion.Citation(
                            citation.evidenceId(), citation.documentId(), citation.snapshotId(), citation.order(),
                            QaQuestion.EvidenceSourceType.valueOf(citation.sourceType().name()),
                            citation.projectIdentifier(), citation.branch(), citation.commit(),
                            citation.repositoryPath(), citation.title(), citation.sourceUpdatedAt(),
                            metadata == null ? null : metadata.scopeType(),
                            metadata == null ? null : metadata.knowledgeSourceType(),
                            metadata == null ? null : metadata.wikiUrl(),
                            metadata == null ? null : metadata.originalFilename());
                }).toList());
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, Enum<?> value) {
        return value == null ? null : Enum.valueOf(type, value.name());
    }

    private WebQaTrustState trustState(AgentRun run) {
        return WebQaTrustState.from(run.status(), run.resultType(), run.refusalReason(), run.errorCode());
    }

    private NormalizedCreate normalize(CreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("create request is required");
        }
        return new NormalizedCreate(
                requireText(request.operatorId(), "operator"),
                requireText(request.operatorRole(), "operator role"),
                WebQaIdempotencyKey.of(request.idempotencyKey()),
                requireText(request.projectIdentifier(), "project identifier"),
                normalizeOptional(request.branch()),
                request.conversationId(),
                WebQaQuestionText.of(request.question()));
    }

    private GlobalNormalizedCreate normalizeGlobal(GlobalCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("create request is required");
        }
        return new GlobalNormalizedCreate(
                requireText(request.operatorId(), "operator"),
                requireText(request.operatorRole(), "operator role"),
                WebQaIdempotencyKey.of(request.idempotencyKey()),
                request.conversationId(),
                WebQaQuestionText.of(request.question()));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private ConversationSummary summary(WebQaConversationRecord conversation, String projectName, String operatorId) {
        List<WebQaQuestionRecord> rounds = questions.findByConversation(conversation.id(), MAX_CONVERSATION_ROUNDS + 1);
        if (rounds.isEmpty()) {
            throw new IllegalStateException("QA conversation has no rounds");
        }
        WebQaQuestionRecord last = rounds.getLast();
        AgentRun run = agents.get(last.runId(), operatorId);
        String lastQuestion = messages.findByQuestionId(last.id()).stream()
                .filter(value -> value.role() == WebQaMessageRole.USER)
                .map(WebQaMessageRecord::content).findFirst().orElse(conversation.title());
        // 范围标注由项目归属推导：project_id 为空即全局会话，项目显示名仅跨项目列表提供。
        String scope = conversation.projectId() == null ? "GLOBAL" : "PROJECT";
        return new ConversationSummary(conversation.id(), conversation.projectIdentifier(), projectName, scope,
                conversation.title(), lastQuestion, QaQuestion.Status.valueOf(run.status().name()),
                conversation.createdAt(), conversation.updatedAt(), conversation.lastQuestionAt());
    }

    private WebQaConversationRecord fromRow(WebQaGlobalConversationRecord row) {
        return new WebQaConversationRecord(row.id(), row.operatorId(), row.projectId(), row.projectIdentifier(),
                row.title(), row.createdAt(), row.updatedAt(), row.lastQuestionAt());
    }

    private List<AgentService.ConversationMessage> completedHistory(Long conversationId, String operatorId) {
        List<WebQaQuestionRecord> rounds = questions.findByConversation(conversationId, MAX_CONVERSATION_ROUNDS + 1);
        List<List<AgentService.ConversationMessage>> completed = new ArrayList<>();
        for (WebQaQuestionRecord round : rounds) {
            AgentRun run = agents.get(round.runId(), operatorId);
            if (run.status() != AgentRun.Status.COMPLETED) {
                continue;
            }
            try {
                materializer.materialize(round, run);
            } catch (RuntimeException exception) {
                // 投影是自愈优化；历史上下文仍必须以已提交 Agent 终态为准，不得因消息表短暂缺失丢掉最终回答。
                log.warn("web_qa conversation history projection deferred traceId={} questionId={} runId={} "
                                + "errorCode=WEB_QA_MESSAGE_PROJECTION_FAILED",
                        traceId(), round.id(), run.runId());
            }
            List<AgentService.ConversationMessage> roundMessages = new ArrayList<>(
                    messages.findByQuestionId(round.id()).stream()
                    .filter(value -> value.role() == WebQaMessageRole.USER || value.role() == WebQaMessageRole.ASSISTANT)
                    .map(value -> new AgentService.ConversationMessage(
                            value.role().name(), value.content(), value.createdAt()))
                    .toList());
            boolean hasUser = roundMessages.stream().anyMatch(value -> value.role().equals("USER"));
            boolean hasAssistant = roundMessages.stream().anyMatch(value -> value.role().equals("ASSISTANT"));
            if (!hasUser) {
                continue;
            }
            if (!hasAssistant) {
                if (run.resultText() == null || run.resultText().isBlank() || run.finishedAt() == null) {
                    throw new IllegalStateException("completed QA history run has no public result");
                }
                roundMessages.add(new AgentService.ConversationMessage(
                        "ASSISTANT", run.resultText(), run.finishedAt()));
            }
            completed.add(List.copyOf(roundMessages));
        }
        List<AgentService.ConversationMessage> selected = new ArrayList<>();
        int characters = 0;
        int selectedRounds = 0;
        for (int index = completed.size() - 1; index >= 0 && selectedRounds < MAX_CONTEXT_ROUNDS; index--) {
            List<AgentService.ConversationMessage> round = completed.get(index);
            int roundCharacters = round.stream().mapToInt(value ->
                    value.content().codePointCount(0, value.content().length())).sum();
            if (characters + roundCharacters > MAX_CONTEXT_CODE_POINTS) {
                break;
            }
            selected.addAll(0, round);
            characters += roundCharacters;
            selectedRounds++;
        }
        return List.copyOf(selected);
    }

    private String truncate(String value, int maximumCodePoints) {
        int length = value.codePointCount(0, value.length());
        return length <= maximumCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    private void requireSameRequest(WebQaQuestionRecord existing, String requestHash) {
        if (!requestHash.equals(existing.requestHash())) {
            log.warn("web_qa create failed traceId={} questionId={} runId={} project={} branch={} "
                            + "errorCode={}",
                    traceId(existing.id()), existing.id(), existing.runId(), existing.projectIdentifier(),
                    existing.branch(), AgentRun.ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
            throw new AgentRequestException(AgentRun.ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
        }
    }

    private String shortHash(String value) {
        return hash(value).substring(0, 12);
    }

    private String traceId(Long fallbackId) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? fallbackId.toString() : current;
    }

    private String traceId() {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? "background" : current;
    }

    private String agentIdempotencyKey(String clientKey) {
        return "web-qa:" + hash(clientKey);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record NormalizedCreate(
            String operatorId,
            String operatorRole,
            WebQaIdempotencyKey idempotencyKey,
            String projectIdentifier,
            String branch,
            Long conversationId,
            WebQaQuestionText question
    ) {
    }

    private record GlobalNormalizedCreate(
            String operatorId,
            String operatorRole,
            WebQaIdempotencyKey idempotencyKey,
            Long conversationId,
            WebQaQuestionText question
    ) {
    }
}
