package io.github.loredock.auth.api;

/**
 * 已通过 Web 凭据校验的操作者摘要，不包含密码哈希或会话令牌。
 *
 * @param username 用于会话与审计的稳定账号标识
 * @param displayName 面向界面展示的账号名称
 * @param role 服务端解析的 Web 角色
 */
public record AuthenticatedActor(String username, String displayName, WebRole role) {
}
