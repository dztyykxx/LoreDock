package io.github.loredock.code.model.result;

import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import java.time.Instant;

/** 普通活动状态视图；NOT_INDEXED 时快照、commit、索引时间和计数均为空。 */
public record ActiveCodeSnapshotView(
        String projectIdentifier,
        String branch,
        CodeSnapshotAvailability status,
        Long snapshotId,
        String commit,
        Instant indexedAt,
        Long indexedFileCount,
        CodeSnapshotChangeHint changeHint
) {
}
