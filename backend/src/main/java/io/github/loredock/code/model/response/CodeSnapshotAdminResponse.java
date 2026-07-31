package io.github.loredock.code.model.response;

import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import java.time.Instant;

/** 管理快照响应；不声明对象键和索引目录字段，从类型层阻止物理信息泄漏。 */
public record CodeSnapshotAdminResponse(
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
