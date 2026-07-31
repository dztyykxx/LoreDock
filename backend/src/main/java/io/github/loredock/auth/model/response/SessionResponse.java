package io.github.loredock.auth.model.response;

import io.github.loredock.auth.model.enums.WebRole;

/**
 * 登录成功与会话查询共用的安全身份响应，不包含 Token、Cookie 或密码哈希。
 *
 * @param username 稳定账号标识
 * @param displayName 展示名称
 * @param role 服务端解析角色
 */
public record SessionResponse(String username, String displayName, WebRole role) {
}
