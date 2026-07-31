package io.github.loredock.code.api;

import java.time.Instant;

/**
 * 当前分支活动代码快照的公开状态。
 *
 * @param projectIdentifier 项目标识
 * @param branch 实际分支
 * @param status 已索引或尚未索引
 * @param snapshotId 活动快照标识
 * @param commit 活动 commit
 * @param indexedAt 索引时间
 * @param indexedFileCount 已索引文件数
 * @param changeHint 相对上一活动快照的变化提示
 */
public record ActiveCodeState(
        String projectIdentifier,
        String branch,
        Status status,
        Long snapshotId,
        String commit,
        Instant indexedAt,
        Long indexedFileCount,
        ChangeHint changeHint
) {
    public enum Status {
        INDEXED,
        NOT_INDEXED
    }

    public enum ChangeHint {
        INITIAL,
        UNCHANGED,
        CHANGED
    }
}
