package io.github.loredock.code.model.response;

import io.github.loredock.job.api.JobService;
import java.time.Instant;

/** 可轮询代码任务响应；失败信息仅允许稳定错误码和脱敏摘要。 */
public record CodeSnapshotJobResponse(
        Long snapshotId,
        Long jobId,
        Long projectId,
        Long branchId,
        String commit,
        JobService.Status status,
        int progress,
        long indexedFileCount,
        long ignoredFileCount,
        Instant createdAt,
        Instant finishedAt,
        String failureCode,
        String failureSummary
) {
}
