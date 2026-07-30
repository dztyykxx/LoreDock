package io.github.loredock.identity.application;

/**
 * 为异步响应捕获不透明会话租约并复核其存活状态；租约实现不得公开令牌值或写入日志。
 */
public interface WebSessionContinuityPort {
    /** 不透明会话租约标记，只允许认证基础设施实现。 */
    interface Lease {
    }

    /** @return 当前请求的会话租约；无有效会话时抛出登录失败 */
    Lease capture();

    /**
     * @param lease 建连时捕获的不透明租约
     * @param expectedUsername 建连时已认证的操作者
     * @return 会话仍有效且身份未改变时为 true
     */
    boolean isValid(Lease lease, String expectedUsername);
}
