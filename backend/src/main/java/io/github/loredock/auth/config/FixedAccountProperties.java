package io.github.loredock.auth.config;

import io.github.loredock.auth.api.WebRole;

/**
 * 单个固定 Web 账号的强类型配置；passwordHash 必须是带盐 BCrypt 值。
 *
 * @param username 稳定账号标识
 * @param displayName 展示名称
 * @param role 固定服务端角色
 * @param passwordHash BCrypt 哈希，禁止写入日志或响应
 */
public record FixedAccountProperties(
        String username,
        String displayName,
        WebRole role,
        String passwordHash
) {
}
