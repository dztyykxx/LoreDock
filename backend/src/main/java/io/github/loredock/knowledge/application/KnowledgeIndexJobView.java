package io.github.loredock.knowledge.application;

import io.github.loredock.job.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/** 对管理员可见的知识重建任务状态，不包含 owner、对象键或内部异常。 */
public record KnowledgeIndexJobView(
        UUID id,
        JobStatus status,
        int progress,
        Instant startedAt,
        Instant finishedAt,
        String failureSummary
) {
}
