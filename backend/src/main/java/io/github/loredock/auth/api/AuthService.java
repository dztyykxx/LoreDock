package io.github.loredock.auth.api;

import java.util.Optional;

/** QA 与 Feedback 使用的当前操作者和长连接会话复核契约。 */
public interface AuthService {

    /** @return 当前有效身份；无 Web 上下文或未登录时为空 */
    Optional<AuthenticatedActor> current();

    /** @return 当前有效身份；未登录时抛出稳定认证异常 */
    AuthenticatedActor currentSession();

    /** @return 不暴露 Token 内容的当前会话租约 */
    SessionLease capture();

    /** @param lease 不透明会话租约 @param expectedUsername 预期账号 @return 租约仍有效且属于该账号 */
    boolean isValid(SessionLease lease, String expectedUsername);

    /** 只允许传递和复核、不提供令牌访问能力的不透明租约。 */
    interface SessionLease {
    }
}
