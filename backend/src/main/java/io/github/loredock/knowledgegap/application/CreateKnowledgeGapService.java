package io.github.loredock.knowledgegap.application;

import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.application.QueryWebQaDetailCommand;
import io.github.loredock.qa.application.QueryWebQaQuestionUseCase;
import io.github.loredock.qa.application.WebQaMessageRecord;
import io.github.loredock.qa.application.WebQaQuestionNotFoundException;
import io.github.loredock.qa.application.WebQaQuestionSnapshot;
import io.github.loredock.qa.domain.WebQaMessageRole;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 在一个短事务中固定反馈范围和运行时事实；该用例不会调用知识、索引或 Agent 写能力。
 */
@Service
@Slf4j
public class CreateKnowledgeGapService implements CreateKnowledgeGapUseCase {
    private final ProjectQueryUseCase projects;
    private final QueryWebQaQuestionUseCase questions;
    private final KnowledgeGapFeedbackRepository feedback;
    private final TimeProvider timeProvider;

    /**
     * @param projects 启用项目与分支解析
     * @param questions 受操作者和项目约束的问答只读快照
     * @param feedback 反馈及引用仓储
     * @param timeProvider UTC 时间源
     */
    public CreateKnowledgeGapService(
            ProjectQueryUseCase projects,
            QueryWebQaQuestionUseCase questions,
            KnowledgeGapFeedbackRepository feedback,
            TimeProvider timeProvider
    ) {
        this.projects = projects;
        this.questions = questions;
        this.feedback = feedback;
        this.timeProvider = timeProvider;
    }

    @Override
    @Transactional
    public KnowledgeGapFeedbackSnapshot create(CreateKnowledgeGapCommand command) {
        ProjectDetailView project = projects.getEnabledProject(command.projectIdentifier(), command.branch());
        BranchView branch = project.branches().stream()
                .filter(candidate -> candidate.name().equals(project.selectedBranch()))
                .findFirst().orElseThrow(() -> new IllegalStateException("selected project branch is missing"));
        LinkedFacts linked = command.questionId() == null
                ? LinkedFacts.manual(command.question())
                : linkedFacts(command, project, branch);
        String requestHash = hash(canonical(command, project, branch, linked));

        var existing = feedback.findByOperatorAndIdempotencyKey(command.operatorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return reuse(existing.get(), requestHash);
        }

        Instant now = timeProvider.now();
        UUID feedbackId = UUID.randomUUID();
        KnowledgeGapFeedbackRecord record = new KnowledgeGapFeedbackRecord(
                feedbackId, command.operatorId(), command.idempotencyKey(), requestHash,
                project.id(), project.identifier(), branch.id(), branch.name(),
                linked.questionId(), linked.runId(), command.type(), KnowledgeGapStatus.OPEN,
                linked.question(), command.note(), linked.run() == null ? null : linked.run().resultType(),
                linked.run() == null ? null : linked.run().refusalReason(),
                linked.run() == null ? null : linked.run().errorCode(),
                now, now, command.operatorId(), command.operatorId());
        if (!feedback.insertIfAbsent(record)) {
            KnowledgeGapFeedbackRecord concurrent = feedback.findByOperatorAndIdempotencyKey(
                    command.operatorId(), command.idempotencyKey()).orElseThrow();
            return reuse(concurrent, requestHash);
        }

        List<KnowledgeGapCitationRecord> citations = new ArrayList<>();
        if (linked.run() != null) {
            for (var citation : linked.run().citations()) {
                citations.add(new KnowledgeGapCitationRecord(
                        UUID.randomUUID(), feedbackId, linked.runId(), citation.evidenceId(),
                        citations.size() + 1, now));
            }
        }
        feedback.insertCitations(citations);
        log.info("knowledge_gap created traceId={} feedbackId={} project={} branch={} type={} status={} "
                        + "questionId={} runId={} citationCount={} questionLength={} noteLength={}",
                traceId(), feedbackId, project.identifier(), branch.name(), command.type(), KnowledgeGapStatus.OPEN,
                linked.questionId(), linked.runId(), citations.size(), codePoints(linked.question()),
                codePoints(command.note()));
        return new KnowledgeGapFeedbackSnapshot(record, citations.stream()
                .map(KnowledgeGapCitationRecord::evidenceId).toList());
    }

    private LinkedFacts linkedFacts(
            CreateKnowledgeGapCommand command,
            ProjectDetailView project,
            BranchView branch
    ) {
        WebQaQuestionSnapshot snapshot = questions.detail(new QueryWebQaDetailCommand(
                command.operatorId(), project.identifier(), command.questionId()));
        if (!snapshot.question().projectId().equals(project.id())
                || !snapshot.question().branchId().equals(branch.id())
                || !snapshot.question().branch().equals(branch.name())) {
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
        List<UUID> evidenceIds = feedback.findCitations(existing.id()).stream()
                .map(KnowledgeGapCitationRecord::evidenceId).toList();
        log.info("knowledge_gap reused traceId={} feedbackId={} project={} branch={} status={} citationCount={}",
                traceId(), existing.id(), existing.projectIdentifier(), existing.branch(), existing.status(),
                evidenceIds.size());
        return new KnowledgeGapFeedbackSnapshot(existing, evidenceIds);
    }

    private String canonical(
            CreateKnowledgeGapCommand command,
            ProjectDetailView project,
            BranchView branch,
            LinkedFacts linked
    ) {
        return String.join("\n",
                project.id().toString(), project.identifier(), branch.id().toString(), branch.name(),
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

    private record LinkedFacts(UUID questionId, UUID runId, String question, AgentRunSnapshot run) {
        private static LinkedFacts manual(String question) {
            return new LinkedFacts(null, null, question, null);
        }
    }
}
