package io.github.loredock.code.application;

import java.util.UUID;

/** 同一活动快照的新 generation 替换命令。 */
public record CodeSnapshotReindexActivation(
        UUID snapshotId,
        UUID generationId,
        UUID jobId,
        UUID branchId,
        long indexedFileCount,
        long ignoredFileCount
) {
}
