package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.job.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/** PENDING、RUNNING、SUCCEEDED 或 FAILED 的脱敏知识任务响应。 */
public record KnowledgeIndexJobResponse(
        UUID id,
        JobStatus status,
        int progress,
        Instant startedAt,
        Instant finishedAt,
        String failureSummary
) {
}
