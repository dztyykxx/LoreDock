package io.github.loredock.code.infrastructure.web;

import io.github.loredock.code.application.CodeSnapshotAvailability;
import io.github.loredock.code.application.CodeSnapshotChangeHint;

import java.time.Instant;
import java.util.UUID;

/** 普通活动快照状态响应；类型中不包含 generation、对象键和物理目录。 */
public record ActiveCodeSnapshotResponse(
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
