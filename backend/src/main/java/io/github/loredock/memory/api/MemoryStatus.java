package io.github.loredock.memory.api;

/**
 * 记忆状态：检索、注入与按需加载只覆盖 {@code ACTIVE} 记录。
 */
public enum MemoryStatus {

    /** 正常可用；检索/注入/全文加载均可见。 */
    ACTIVE,

    /** 人工停用；检索与加载不可见，记录保留、可重新启用，不得被提炼自动复活。 */
    DISABLED
}
