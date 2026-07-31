package io.github.loredock.code.model.result;

import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import java.time.Instant;

/** 管理快照摘要；物理对象和 generation 位置始终保留在基础设施内部。 */
public record CodeSnapshotAdminView(
        Long snapshotId,
        Long projectId,
        Long branchId,
        String commit,
        CodeSnapshotStatus status,
        long indexedFileCount,
        long ignoredFileCount,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
