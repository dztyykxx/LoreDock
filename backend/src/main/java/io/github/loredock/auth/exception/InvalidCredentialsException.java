package io.github.loredock.auth.exception;

/**
 * 登录凭据被拒绝；异常消息固定且不区分账号不存在与密码错误。
 */
public class InvalidCredentialsException extends RuntimeException {

    /**
     * 创建不携带账号、密码或哈希的统一凭据失败。
     */
    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}
