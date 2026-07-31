package io.github.loredock.qa.service;

import io.github.loredock.agent.api.AgentRequestException;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentRunNotFoundException;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.api.QaQuestionNotFoundException;
import io.github.loredock.qa.api.QaQuestionPage;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.converter.WebQaCursorCodec;
import io.github.loredock.qa.model.WebQaIdempotencyKey;
import io.github.loredock.qa.model.WebQaQuestionText;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
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
    private final ProjectService projects;
    private final AgentService agents;
    private final WebQaQuestionDataService questions;
    private final WebQaMessageDataService messages;
    private final DefaultWebQaAssistantMessageMaterializer materializer;
    private final Clock timeProvider;

    /**
     * @param projects 启用项目和明确分支解析
     * @param agents Agent 运行受理与安全查询契约
     * @param questions 问答身份仓储
     * @param messages 问答消息仓储
     * @param materializer 终态助手消息自愈投影
     * @param timeProvider UTC 时间源
     */
    public QaServiceImpl(
            ProjectService projects,
            AgentService agents,
            WebQaQuestionDataService questions,
            WebQaMessageDataService messages,
            DefaultWebQaAssistantMessageMaterializer materializer,
            Clock timeProvider
    ) {
        this.projects = projects;
        this.agents = agents;
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

        AgentRun run = agents.start(new AgentService.StartRequest(
                agentIdempotencyKey(command.idempotencyKey().value()), command.operatorId(), command.operatorRole(),
                project.projectIdentifier(), project.branchName(), command.question().value()));
        Instant createdAt = timeProvider.instant();
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                null, command.operatorId(), command.idempotencyKey().value(), requestHash,
                run.scope().projectId(), run.scope().projectIdentifier(), run.scope().branchId(), run.scope().branch(),
                run.runId(), createdAt);
        var insertedQuestionId = questions.insertIfAbsent(question);
        if (insertedQuestionId.isEmpty()) {
            // 并发请求只能复读数据库胜者；不得以本事务内临时对象覆盖已提交事实。
            WebQaQuestionRecord raced = questions.findByOperatorAndIdempotencyKey(
                            command.operatorId(), command.idempotencyKey().value())
                    .orElseThrow(() -> new IllegalStateException("web QA idempotent winner missing"));
            requireSameRequest(raced, requestHash);
            return snapshot(raced, agents.get(raced.runId(), command.operatorId()));
        }
        question = new WebQaQuestionRecord(
                insertedQuestionId.orElseThrow(), question.operatorId(), question.idempotencyKey(),
                question.requestHash(), question.projectId(), question.projectIdentifier(), question.branchId(),
                question.branch(), question.runId(), question.createdAt());
        WebQaMessageRecord pendingUserMessage = new WebQaMessageRecord(
                null, question.id(), WebQaMessageRole.USER, command.question().value(),
                null, null, createdAt);
        var insertedMessageId = messages.insertIfAbsent(pendingUserMessage);
        if (insertedMessageId.isEmpty()) {
            throw new IllegalStateException("new web QA user message role already exists");
        }
        WebQaMessageRecord userMessage = new WebQaMessageRecord(
                insertedMessageId.orElseThrow(), pendingUserMessage.questionId(), pendingUserMessage.role(),
                pendingUserMessage.content(), pendingUserMessage.resultType(), pendingUserMessage.refusalReason(),
                pendingUserMessage.createdAt());
        log.info("web_qa create completed traceId={} questionId={} runId={} project={} branch={} status={} "
                        + "questionLength={} questionDigest={}",
                traceId(question.id()), question.id(), run.runId(), question.projectIdentifier(), question.branch(),
                run.status(), command.question().codePointLength(), shortHash(command.question().value()));
        return new WebQaQuestionSnapshot(
                question, run, trustState(run), List.of(userMessage));
    }

    @Override
    public QaQuestionPage history(HistoryQuery query) {
        requireIdentity(query.operatorId(), query.projectIdentifier());
        if (query.limit() < 1 || query.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA page size out of range");
        }
        ProjectScope project = enabledProject(query.projectIdentifier());
        WebQaCursor after = query.cursor() == null || query.cursor().isBlank()
                ? null : WebQaCursorCodec.decode(query.cursor());
        List<WebQaQuestionRecord> records = questions.findHistory(
                query.operatorId(), project.projectId(), after, query.limit() + 1);
        boolean hasMore = records.size() > query.limit();
        List<WebQaQuestionRecord> visible = hasMore ? records.subList(0, query.limit()) : records;
        List<WebQaQuestionSnapshot> snapshots = new ArrayList<>(visible.size());
        for (WebQaQuestionRecord question : visible) {
            snapshots.add(snapshot(question, agents.get(question.runId(), query.operatorId())));
        }
        String nextCursor = hasMore && !visible.isEmpty()
                ? WebQaCursorCodec.encode(new WebQaCursor(visible.getLast().createdAt(), visible.getLast().id()))
                : null;
        log.info("web_qa history queried traceId={} project={} resultCount={} hasMore={}",
                traceId(), project.projectIdentifier(), snapshots.size(), hasMore);
        return new QaQuestionPage(snapshots.stream().map(this::toApi).toList(), nextCursor);
    }

    @Override
    public QaQuestion detail(DetailQuery query) {
        WebQaQuestionSnapshot snapshot = detailSnapshot(query);
        return toApi(snapshot);
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
        ProjectScope project = enabledProject(query.projectIdentifier());
        WebQaQuestionRecord question = questions.findVisibleById(
                        query.operatorId(), project.projectId(), query.questionId())
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
                snapshot.question().id(), run.runId(),
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
            WebQaQuestionText question
    ) {
    }
}
