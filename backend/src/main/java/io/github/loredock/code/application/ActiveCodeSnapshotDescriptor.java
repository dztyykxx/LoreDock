package io.github.loredock.code.application;

import java.time.Instant;
import java.util.UUID;

/** 一次数据库读取固定的活动 snapshot/generation 描述符；generation 仅供内部索引访问。 */
public record ActiveCodeSnapshotDescriptor(
        UUID projectId,
        UUID branchId,
        UUID snapshotId,
        UUID generationId,
        String commit,
        Instant indexedAt,
        long indexedFileCount,
        CodeSnapshotChangeHint changeHint
) {
}
