package io.github.loredock.knowledge.model;

/**
 * 单调递增的文档修订契约；只用于并发与索引同步，不代表保存了正文历史版本。
 *
 * @param value 正整数修订号
 */
public record DocumentRevision(long value) {

    public DocumentRevision {
        if (value < 1) {
            throw new IllegalArgumentException("document revision must be positive");
        }
    }

    /** @return 增加一后的修订号。 */
    public DocumentRevision next() {
        return new DocumentRevision(Math.incrementExact(value));
    }
}
