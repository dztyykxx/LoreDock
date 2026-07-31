package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.snapshot.ZipArchiveEntryContent;
import io.github.loredock.knowledge.model.snapshot.ZipArchiveInspection;
import java.util.List;

/** 同一个安全临时 ZIP 上完成中央目录检查和候选正文读取的结果。 */
public record ZipArchiveReadResult(
        ZipArchiveInspection inspection,
        List<ZipArchiveEntryContent> contents
) {
}
