package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.platform.audit.AuditMetadata;

import java.time.Instant;
import java.util.UUID;

/** 数据库快照业务记录；对象键只在内部协调与重建中使用，禁止进入 HTTP 视图。 */
public record CodeSnapshotRecord(
        UUID id,
        UUID projectId,
        UUID branchId,
        String commit,
        String inputObjectKey,
        CodeSnapshotStatus status,
        UUID previousSnapshotId,
        long indexedFileCount,
        long ignoredFileCount,
        Instant indexedAt,
        AuditMetadata audit
) {
}
