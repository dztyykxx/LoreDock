package io.github.loredock.code.model.result;


/** 已发布 generation 的候选激活命令。 */
public record CodeSnapshotBuildActivation(
        Long snapshotId,
        Long generationId,
        Long jobId,
        Long branchId,
        long indexedFileCount,
        long ignoredFileCount
) {
}
