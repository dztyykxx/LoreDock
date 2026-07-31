package io.github.loredock.code.model.result;


/** 同一活动快照的新 generation 替换命令。 */
public record CodeSnapshotReindexActivation(
        Long snapshotId,
        Long generationId,
        Long jobId,
        Long branchId,
        long indexedFileCount,
        long ignoredFileCount
) {
}
