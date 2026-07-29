package io.github.loredock.identity.application;

/**
 * 退出当前浏览器 Web 会话；重复调用必须安全且不影响其他浏览器会话。
 */
@FunctionalInterface
public interface LogoutUseCase {

    /**
     * 清除当前请求携带的会话；无有效会话时也正常返回。
     */
    void logout();
}
