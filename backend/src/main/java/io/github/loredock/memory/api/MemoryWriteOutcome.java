package io.github.loredock.memory.api;

/**
 * 写入判断结论：冲突仍写但结论区别于普通创建，供日志与预算观测区分。
 */
public enum MemoryWriteOutcome {

    /** 无既有重复、不具冲突：新写一条记忆。 */
    CREATED,

    /** 与既有记忆语义冲突但仍写入，两条均保持 ACTIVE，由模型在采纳时刻按上下文择优。 */
    CONFLICT_CREATED,

    /** 与既有记忆（含 DISABLED）语义重复：跳过，不改动既有记忆；停用记忆不被复活。 */
    SKIP_DUPLICATE,

    /** 不具长期价值（一次性任务指令、与产出偏好无关）：拒写。 */
    SKIP_NOT_WORTH
}
