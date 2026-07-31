package io.github.loredock.auth.config;

import io.github.loredock.auth.api.WebRole;

/**
 * 固定账号目录提供给身份用例的内部凭据记录；密码哈希只能用于服务端校验，不得进入 API、日志或审计字段。
 *
 * @param username 稳定账号标识
 * @param displayName 展示名称
 * @param role 固定角色
 * @param passwordHash 带盐 BCrypt 哈希
 */
public record FixedAccount(String username, String displayName, WebRole role, String passwordHash) {
}
