package io.github.loredock.code.infrastructure.web;

import io.github.loredock.job.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/** 可轮询代码任务响应；失败信息仅允许稳定错误码和脱敏摘要。 */
public record CodeSnapshotJobResponse(
        UUID snapshotId,
        UUID jobId,
        UUID projectId,
        UUID branchId,
        String commit,
        JobStatus status,
        int progress,
        long indexedFileCount,
        long ignoredFileCount,
        Instant createdAt,
        Instant finishedAt,
        String failureCode,
        String failureSummary
) {
}
