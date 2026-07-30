package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeSnapshotStatus;

import java.time.Instant;
import java.util.UUID;

/** 管理快照摘要；物理对象和 generation 位置始终保留在基础设施内部。 */
public record CodeSnapshotAdminView(
        UUID snapshotId,
        UUID projectId,
        UUID branchId,
        String commit,
        CodeSnapshotStatus status,
        long indexedFileCount,
        long ignoredFileCount,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
