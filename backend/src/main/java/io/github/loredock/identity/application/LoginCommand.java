package io.github.loredock.identity.application;

/**
 * 登录凭据输入；对象生命周期应限制在单次请求中，任何失败路径都不得记录密码内容。
 *
 * @param username 账号标识
 * @param password 明文候选密码
 */
public record LoginCommand(String username, String password) {
}
