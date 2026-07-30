package io.github.loredock.knowledge.application;

/** 已通过完整批次安全检查后读取的 Markdown 条目正文。 */
public record ZipArchiveEntryContent(int ordinal, byte[] bytes) {
}
