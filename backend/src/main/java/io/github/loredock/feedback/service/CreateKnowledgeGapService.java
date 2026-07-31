package io.github.loredock.feedback.service;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.feedback.exception.KnowledgeGapIdempotencyConflictException;
import io.github.loredock.feedback.model.command.CreateKnowledgeGapCommand;
import io.github.loredock.feedback.model.enums.KnowledgeGapStatus;
import io.github.loredock.feedback.model.result.KnowledgeGapCitationRecord;
import io.github.loredock.feedback.model.result.KnowledgeGapFeedbackRecord;
import io.github.loredock.feedback.model.snapshot.KnowledgeGapFeedbackSnapshot;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.exception.WebQaQuestionNotFoundException;
import io.github.loredock.qa.model.command.QueryWebQaDetailCommand;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import io.github.loredock.qa.service.QueryWebQaQuestionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个短事务中固定反馈范围和运行时事实；该用例不会调用知识、索引或 Agent 写能力。
 */
@Service
@Slf4j
public class CreateKnowledgeGapService {
    private final ProjectService projects;
    private final QueryWebQaQuestionService questions;
    private final KnowledgeGapDataService feedback;
    private final Clock timeProvider;

    /**
     * @param projects 启用项目与分支解析
     * @param questions 受操作者和项目约束的问答只读快照
     * @param feedback 反馈及引用仓储
     * @param timeProvider UTC 时间源
     */
    public CreateKnowledgeGapService(
            ProjectService projects,
            QueryWebQaQuestionService questions,
            KnowledgeGapDataService feedback,
            Clock timeProvider
    ) {
        this.projects = projects;
        this.questions = questions;
        this.feedback = feedback;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public KnowledgeGapFeedbackSnapshot create(CreateKnowledgeGapCommand command) {
        ProjectScope project = projects.resolveEnabledScope(command.projectIdentifier(), command.branch());
        LinkedFacts linked = command.questionId() == null
                ? LinkedFacts.manual(command.question())
                : linkedFacts(command, project);
        String requestHash = hash(canonical(command, project, linked));

        var existing = feedback.findByOperatorAndIdempotencyKey(command.operatorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return reuse(existing.get(), requestHash);
        }

        Instant now = timeProvider.instant();
        KnowledgeGapFeedbackRecord pendingRecord = new KnowledgeGapFeedbackRecord(
                null, command.operatorId(), command.idempotencyKey(), requestHash,
                project.projectId(), project.projectIdentifier(), project.branchId(), project.branchName(),
                linked.questionId(), linked.runId(), command.type(), KnowledgeGapStatus.OPEN,
                linked.question(), command.note(), linked.run() == null ? null : linked.run().resultType(),
                linked.run() == null ? null : linked.run().refusalReason(),
                linked.run() == null ? null : linked.run().errorCode(),
                now, now, command.operatorId(), command.operatorId());
        var insertedFeedbackId = feedback.insertIfAbsent(pendingRecord);
        if (insertedFeedbackId.isEmpty()) {
            KnowledgeGapFeedbackRecord concurrent = feedback.findByOperatorAndIdempotencyKey(
                    command.operatorId(), command.idempotencyKey()).orElseThrow();
            return reuse(concurrent, requestHash);
        }
        Long feedbackId = insertedFeedbackId.orElseThrow();
        KnowledgeGapFeedbackRecord record = new KnowledgeGapFeedbackRecord(
                feedbackId, pendingRecord.operatorId(), pendingRecord.idempotencyKey(), pendingRecord.requestHash(),
                pendingRecord.projectId(), pendingRecord.projectIdentifier(), pendingRecord.branchId(),
                pendingRecord.branch(), pendingRecord.questionId(), pendingRecord.runId(), pendingRecord.type(),
                pendingRecord.status(), pendingRecord.question(), pendingRecord.note(), pendingRecord.resultType(),
                pendingRecord.refusalReason(), pendingRecord.errorCode(), pendingRecord.createdAt(),
                pendingRecord.updatedAt(), pendingRecord.createdBy(), pendingRecord.updatedBy());

        List<KnowledgeGapCitationRecord> citations = new ArrayList<>();
        if (linked.run() != null) {
            for (var citation : linked.run().citations()) {
                citations.add(new KnowledgeGapCitationRecord(
                        null, feedbackId, linked.runId(), citation.evidenceId(),
                        citations.size() + 1, now));
            }
        }
        feedback.insertCitations(citations);
        log.info("knowledge_gap created traceId={} feedbackId={} project={} branch={} type={} status={} "
                        + "questionId={} runId={} citationCount={} questionLength={} noteLength={}",
                traceId(), feedbackId, project.projectIdentifier(), project.branchName(), command.type(),
                KnowledgeGapStatus.OPEN,
                linked.questionId(), linked.runId(), citations.size(), codePoints(linked.question()),
                codePoints(command.note()));
        return new KnowledgeGapFeedbackSnapshot(record, citations.stream()
                .map(KnowledgeGapCitationRecord::evidenceId).toList());
    }

    private LinkedFacts linkedFacts(
            CreateKnowledgeGapCommand command,
            ProjectScope project
    ) {
        WebQaQuestionSnapshot snapshot = questions.detail(new QueryWebQaDetailCommand(
                command.operatorId(), project.projectIdentifier(), command.questionId()));
        if (!snapshot.question().projectId().equals(project.projectId())
                || !snapshot.question().branchId().equals(project.branchId())
                || !snapshot.question().branch().equals(project.branchName())) {
            throw new WebQaQuestionNotFoundException();
        }
        WebQaMessageRecord user = snapshot.messages().stream()
                .filter(message -> message.role() == WebQaMessageRole.USER)
                .findFirst().orElseThrow(() -> new IllegalStateException("web QA user message is missing"));
        return new LinkedFacts(
                snapshot.question().id(), snapshot.run().runId(), user.content(), snapshot.run());
    }

    private KnowledgeGapFeedbackSnapshot reuse(KnowledgeGapFeedbackRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            log.warn("knowledge_gap create failed traceId={} feedbackId={} project={} branch={} "
                            + "errorCode=KNOWLEDGE_GAP_IDEMPOTENCY_CONFLICT",
                    traceId(), existing.id(), existing.projectIdentifier(), existing.branch());
            throw new KnowledgeGapIdempotencyConflictException();
        }
        List<Long> evidenceIds = feedback.findCitations(existing.id()).stream()
                .map(KnowledgeGapCitationRecord::evidenceId).toList();
        log.info("knowledge_gap reused traceId={} feedbackId={} project={} branch={} status={} citationCount={}",
                traceId(), existing.id(), existing.projectIdentifier(), existing.branch(), existing.status(),
                evidenceIds.size());
        return new KnowledgeGapFeedbackSnapshot(existing, evidenceIds);
    }

    private String canonical(
            CreateKnowledgeGapCommand command,
            ProjectScope project,
            LinkedFacts linked
    ) {
        return String.join("\n",
                project.projectId().toString(), project.projectIdentifier(), project.branchId().toString(),
                project.branchName(),
                command.type().name(), linked.questionId() == null ? "" : linked.questionId().toString(),
                linked.question(), command.note() == null ? "" : command.note());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private String traceId() {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? "background" : current;
    }

    private record LinkedFacts(Long questionId, Long runId, String question, AgentRun run) {
        private static LinkedFacts manual(String question) {
            return new LinkedFacts(null, null, question, null);
        }
    }
}
