package io.github.loredock.code.application;

/** 已发布 generation 的业务计数。 */
public record CodeSnapshotGenerationResult(long indexedFileCount, long ignoredFileCount) {
}
