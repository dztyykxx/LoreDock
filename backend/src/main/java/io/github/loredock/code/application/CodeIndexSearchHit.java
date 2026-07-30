package io.github.loredock.code.application;

/** Lucene 基础设施返回的有限纯文本命中。 */
public record CodeIndexSearchHit(String path, String snippet, float score, boolean truncated) {
}
