package io.github.loredock.code.model.result;

/** 已发布 generation 的业务计数。 */
public record CodeSnapshotGenerationResult(long indexedFileCount, long ignoredFileCount) {
}
