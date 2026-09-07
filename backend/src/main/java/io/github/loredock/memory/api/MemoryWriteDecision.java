package io.github.loredock.memory.api;

/** 判断器对候选采取的持久化动作。 */
public enum MemoryWriteDecision {
    CREATE,
    UPDATE,
    SKIP,
    CONFIRM
}
