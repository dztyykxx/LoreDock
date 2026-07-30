package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportItemReason;

/** ZIP 中央目录条目的安全检查结果；FAILED 条目绝不能打开数据流。 */
public record ZipArchiveEntryInspection(
        int ordinal,
        String originalName,
        String normalizedPath,
        long compressedSize,
        long uncompressedSize,
        boolean directory,
        boolean markdownCandidate,
        ImportItemReason rejectedReason
) {
}
