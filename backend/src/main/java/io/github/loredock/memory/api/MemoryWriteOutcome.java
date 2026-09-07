package io.github.loredock.memory.api;

/** 写入判断结论。旧的 {@code CONFLICT_CREATED} 仅为读取历史回执保留。 */
public enum MemoryWriteOutcome {

    /** 无既有重复、不具冲突：新写一条记忆。 */
    CREATED,

    /** 与既有记忆语义冲突但仍写入，两条均保持 ACTIVE。仅兼容旧回执。 */
    CONFLICT_CREATED,

    /** 在原记忆上合并增量或明确更正。 */
    UPDATED,

    /** 语义冲突无法安全自动合并，等待用户确认。 */
    NEEDS_CONFIRMATION,

    /** 与既有记忆（含 DISABLED）语义重复：跳过，不改动既有记忆；停用记忆不被复活。 */
    SKIP_DUPLICATE,

    /** 命中已停用记忆，不复活也不建立替身。 */
    SKIP_DISABLED,

    /** 不具长期价值（一次性任务指令、与产出偏好无关）：拒写。 */
    SKIP_NOT_WORTH
}
