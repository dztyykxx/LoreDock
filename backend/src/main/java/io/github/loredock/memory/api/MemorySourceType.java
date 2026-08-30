package io.github.loredock.memory.api;

/**
 * 记忆来源类型：决定溯源字段的填写要求与写入预算的归集口径。
 */
public enum MemorySourceType {

    /** 由知识整理会话的主 Agent 经 {@code memory_write} 提炼产生，必带来源 run 与会话。 */
    KNOWLEDGE_CURATION,

    /** 由管理员通过管理接口人工创建，不带来源 run 与会话。 */
    MANUAL
}
