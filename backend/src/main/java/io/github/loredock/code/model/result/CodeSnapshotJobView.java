package io.github.loredock.code.model.result;

import io.github.loredock.job.api.JobService;
import java.time.Instant;

/** 管理员可轮询的代码任务视图，不包含代码正文、对象键、物理路径或内部异常。 */
public record CodeSnapshotJobView(
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
