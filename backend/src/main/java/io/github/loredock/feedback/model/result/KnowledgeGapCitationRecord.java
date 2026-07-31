package io.github.loredock.feedback.model.result;

import java.time.Instant;

/** 反馈与同一运行证据的有序关联。 */
public record KnowledgeGapCitationRecord(
        Long id, Long feedbackId, Long runId, Long evidenceId, int order, Instant createdAt
) {
}
