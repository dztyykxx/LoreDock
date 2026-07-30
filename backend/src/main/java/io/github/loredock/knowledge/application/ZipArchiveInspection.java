package io.github.loredock.knowledge.application;

import java.util.List;

/** 批次级 ZIP 检查结果；只有完整成功返回后才允许启动任何文档事务。 */
public record ZipArchiveInspection(
        List<ZipArchiveEntryInspection> entries,
        long totalUncompressedSize
) {
}
