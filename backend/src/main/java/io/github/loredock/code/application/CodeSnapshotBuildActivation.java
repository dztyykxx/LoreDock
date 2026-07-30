package io.github.loredock.code.application;

import java.util.UUID;

/** 已发布 generation 的候选激活命令。 */
public record CodeSnapshotBuildActivation(
        UUID snapshotId,
        UUID generationId,
        UUID jobId,
        UUID branchId,
        long indexedFileCount,
        long ignoredFileCount
) {
}
