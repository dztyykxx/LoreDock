package io.github.loredock.code.api;

import java.time.Instant;
import java.util.List;

/** @param items 有界且固定到同一活动快照的代码命中 */
public record CodeMatches(List<Match> items) {
    public CodeMatches {
        items = List.copyOf(items);
    }

    /**
     * @param projectIdentifier 项目标识
     * @param branch 实际分支
     * @param snapshotId 活动快照标识
     * @param commit 活动 commit
     * @param indexedAt 索引时间
     * @param path 仓库相对路径
     * @param snippet 有界命中片段
     * @param score 相关性
     * @param truncated 片段是否截断
     */
    public record Match(
            String projectIdentifier,
            String branch,
            Long snapshotId,
            String commit,
            Instant indexedAt,
            String path,
            String snippet,
            float score,
            boolean truncated
    ) {
    }
}
