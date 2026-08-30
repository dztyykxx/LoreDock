package io.github.loredock.memory.api;

/**
 * 记忆范围：记忆是共享的（不按用户隔离），只区分两级范围。
 */
public enum MemoryScope {

    /** 通用记忆，不绑定项目，所有项目内会话均可见。 */
    GLOBAL,

    /** 项目记忆，必须关联一个存在且启用的项目，仅该项目内的会话可见。 */
    PROJECT
}
