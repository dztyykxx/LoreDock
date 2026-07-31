package io.github.loredock.qa.service;

import io.github.loredock.agent.exception.AgentRequestException;
import io.github.loredock.agent.model.command.StartProjectQaRunCommand;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.service.AgentRunQueryService;
import io.github.loredock.agent.service.StartProjectQaRunService;
import io.github.loredock.project.model.result.ProjectDetailView;
import io.github.loredock.project.service.ProjectApplicationService;
import io.github.loredock.qa.model.command.CreateWebQaQuestionCommand;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个短事务中创建问答身份、用户消息和 Agent 受理事实；调度由 Agent 协调器在最外层提交后执行。
 */
@Service
@Slf4j
public class CreateWebQaQuestionService {
    private final ProjectApplicationService projects;
    private final StartProjectQaRunService starts;
    private final AgentRunQueryService runQueries;
    private final WebQaQuestionDataService questions;
    private final WebQaMessageDataService messages;
    private final Clock timeProvider;

    /**
     * @param projects 启用项目和明确分支解析
     * @param starts Agent 运行受理入口
     * @param runQueries 已有运行安全查询
     * @param questions 问答身份仓储
     * @param messages 问答消息仓储
     * @param timeProvider UTC 时间源
     */
    public CreateWebQaQuestionService(
            ProjectApplicationService projects,
            StartProjectQaRunService starts,
            AgentRunQueryService runQueries,
            WebQaQuestionDataService questions,
            WebQaMessageDataService messages,
            Clock timeProvider
    ) {
        this.projects = projects;
        this.starts = starts;
        this.runQueries = runQueries;
        this.questions = questions;
        this.messages = messages;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public WebQaQuestionSnapshot create(CreateWebQaQuestionCommand command) {
        ProjectDetailView project = projects.getEnabledProject(command.projectIdentifier(), command.branch());
        String requestHash = hash(project.identifier() + "\n" + project.selectedBranch()
                + "\n" + command.question().value());
        var existing = questions.findByOperatorAndIdempotencyKey(
                command.operatorId(), command.idempotencyKey().value());
        if (existing.isPresent()) {
            requireSameRequest(existing.get(), requestHash);
            WebQaQuestionSnapshot reused = snapshot(
                    existing.get(), runQueries.get(existing.get().runId(), command.operatorId()));
            log.info("web_qa create reused traceId={} questionId={} runId={} project={} branch={} status={} "
                            + "questionLength={} questionDigest={}",
                    traceId(existing.get().id()), existing.get().id(), existing.get().runId(),
                    existing.get().projectIdentifier(), existing.get().branch(), reused.run().status(),
                    command.question().codePointLength(), shortHash(command.question().value()));
            return reused;
        }

        AgentRunSnapshot run = starts.start(new StartProjectQaRunCommand(
                agentIdempotencyKey(command.idempotencyKey().value()), command.operatorId(), command.operatorRole(),
                project.identifier(), project.selectedBranch(), command.question().value()));
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
            return snapshot(raced, runQueries.get(raced.runId(), command.operatorId()));
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

    private WebQaQuestionSnapshot snapshot(WebQaQuestionRecord question, AgentRunSnapshot run) {
        List<WebQaMessageRecord> values = messages.findByQuestionId(question.id());
        return new WebQaQuestionSnapshot(question, run, trustState(run), values);
    }

    private WebQaTrustState trustState(AgentRunSnapshot run) {
        return WebQaTrustState.from(run.status(), run.resultType(), run.refusalReason(), run.errorCode());
    }

    private void requireSameRequest(WebQaQuestionRecord existing, String requestHash) {
        if (!requestHash.equals(existing.requestHash())) {
            log.warn("web_qa create failed traceId={} questionId={} runId={} project={} branch={} "
                            + "errorCode={}",
                    traceId(existing.id()), existing.id(), existing.runId(), existing.projectIdentifier(),
                    existing.branch(), AgentErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
            throw new AgentRequestException(AgentErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT);
        }
    }

    private String shortHash(String value) {
        return hash(value).substring(0, 12);
    }

    private String traceId(Long fallbackId) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? fallbackId.toString() : current;
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
}
