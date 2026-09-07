package io.github.loredock.memory.api;

/** 记忆候选与既有记忆的语义关系。 */
public enum MemoryWriteRelation {
    NEW,
    DUPLICATE,
    INCREMENTAL,
    CONFLICT
}
