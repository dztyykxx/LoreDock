package io.github.loredock.knowledge.model.response;

import io.github.loredock.job.api.JobService;
import java.time.Instant;

/** PENDING、RUNNING、SUCCEEDED 或 FAILED 的脱敏知识任务响应。 */
public record KnowledgeIndexJobResponse(
        Long id,
        JobService.Status status,
        int progress,
        Instant startedAt,
        Instant finishedAt,
        String failureSummary
) {
}
