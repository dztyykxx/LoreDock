package io.github.loredock.code.model.result;

import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import java.time.Instant;

/** 一次数据库读取固定的活动 snapshot/generation 描述符；generation 仅供内部索引访问。 */
public record ActiveCodeSnapshotDescriptor(
        Long projectId,
        Long branchId,
        Long snapshotId,
        Long generationId,
        String commit,
        Instant indexedAt,
        long indexedFileCount,
        CodeSnapshotChangeHint changeHint
) {
}
