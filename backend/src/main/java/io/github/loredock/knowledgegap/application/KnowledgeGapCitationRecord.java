package io.github.loredock.knowledgegap.application;

import java.time.Instant;
import java.util.UUID;

/** 反馈与同一运行证据的有序关联。 */
public record KnowledgeGapCitationRecord(
        UUID id, UUID feedbackId, UUID runId, UUID evidenceId, int order, Instant createdAt
) {
}
