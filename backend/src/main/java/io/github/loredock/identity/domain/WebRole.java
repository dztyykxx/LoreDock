package io.github.loredock.identity.domain;

/**
 * Web 固定账号的稳定角色；角色只由服务端账号目录解析，不接受客户端声明。
 */
public enum WebRole {
    /** 可执行项目与分支管理写操作。 */
    ADMIN,
    /** 仅可访问已授权的只读业务入口。 */
    MEMBER
}
