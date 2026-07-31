package io.github.loredock.code.api;

import java.time.Instant;

/**
 * @param projectIdentifier 项目标识
 * @param branch 实际分支
 * @param snapshotId 活动快照标识
 * @param commit 活动 commit
 * @param indexedAt 索引时间
 * @param path 仓库相对路径
 * @param startLine 实际起始行
 * @param endLine 实际结束行
 * @param content 有界纯文本内容
 * @param truncated 文件末尾是否仍有内容
 */
public record CodeExcerpt(
        String projectIdentifier,
        String branch,
        Long snapshotId,
        String commit,
        Instant indexedAt,
        String path,
        int startLine,
        int endLine,
        String content,
        boolean truncated
) {
}
