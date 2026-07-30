package io.github.loredock.code.application;

import java.time.Instant;
import java.util.UUID;

/** 从单个固定活动 generation 的 StoredField 读取的有限纯文本片段。 */
public record CodeSnippetResponse(
        String projectIdentifier,
        String branch,
        UUID snapshotId,
        String commit,
        Instant indexedAt,
        String path,
        int startLine,
        int endLine,
        String content,
        boolean truncated
) {
}
