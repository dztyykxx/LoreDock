package io.github.loredock.code.infrastructure.web;

import io.github.loredock.code.domain.CodeSnapshotStatus;

import java.time.Instant;
import java.util.UUID;

/** 管理快照响应；不声明对象键和索引目录字段，从类型层阻止物理信息泄漏。 */
public record CodeSnapshotAdminResponse(
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
