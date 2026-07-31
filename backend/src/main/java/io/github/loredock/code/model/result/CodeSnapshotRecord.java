package io.github.loredock.code.model.result;

import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.platform.persistence.AuditMetadata;
import java.time.Instant;

/** 数据库快照业务记录；对象键只在内部协调与重建中使用，禁止进入 HTTP 视图。 */
public record CodeSnapshotRecord(
        Long id,
        Long projectId,
        Long branchId,
        String commit,
        String inputObjectKey,
        CodeSnapshotStatus status,
        Long previousSnapshotId,
        long indexedFileCount,
        long ignoredFileCount,
        Instant indexedAt,
        AuditMetadata audit
) {
}
