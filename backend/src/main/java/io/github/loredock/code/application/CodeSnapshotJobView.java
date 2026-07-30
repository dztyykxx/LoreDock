package io.github.loredock.code.application;

import io.github.loredock.job.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/** 管理员可轮询的代码任务视图，不包含代码正文、对象键、物理路径或内部异常。 */
public record CodeSnapshotJobView(
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
