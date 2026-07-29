package io.github.loredock.identity.application;

/**
 * 受保护 Web 能力没有获得有效会话身份。
 */
public class LoginRequiredException extends RuntimeException {

    /**
     * 创建不包含 Cookie 或会话令牌的未登录失败。
     */
    public LoginRequiredException() {
        super("web login required");
    }
}
