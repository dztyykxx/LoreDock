package io.github.loredock.code.api;

/**
 * @param projectIdentifier 项目标识
 * @param branch 实际分支
 * @param path 仓库相对路径
 * @param startLine 起始行（从 1 开始）
 * @param lineCount 最大行数
 * @param snapshotId 固定活动快照标识
 * @param commit 固定活动 commit
 */
public record CodeExcerptQuery(
        String projectIdentifier,
        String branch,
        String path,
        Integer startLine,
        Integer lineCount,
        Long snapshotId,
        String commit
) {
}
