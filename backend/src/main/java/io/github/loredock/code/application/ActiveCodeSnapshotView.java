package io.github.loredock.code.application;

import java.time.Instant;
import java.util.UUID;

/** 普通活动状态视图；NOT_INDEXED 时快照、commit、索引时间和计数均为空。 */
public record ActiveCodeSnapshotView(
        String projectIdentifier,
        String branch,
        CodeSnapshotAvailability status,
        UUID snapshotId,
        String commit,
        Instant indexedAt,
        Long indexedFileCount,
        CodeSnapshotChangeHint changeHint
) {
}
