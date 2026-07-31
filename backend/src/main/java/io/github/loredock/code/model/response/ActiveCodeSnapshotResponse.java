package io.github.loredock.code.model.response;

import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import java.time.Instant;

/** 普通活动快照状态响应；类型中不包含 generation、对象键和物理目录。 */
public record ActiveCodeSnapshotResponse(
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
