package io.github.loredock.identity.infrastructure.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录 HTTP 请求；校验失败不得回显密码拒绝值。
 *
 * @param username 固定账号标识
 * @param password 明文候选密码
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
