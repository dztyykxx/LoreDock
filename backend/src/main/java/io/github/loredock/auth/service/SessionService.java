package io.github.loredock.auth.service;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import io.github.loredock.auth.api.AuthService;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.auth.exception.LoginRequiredException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Sa-Token Web 会话服务。集中处理建立、查询、退出和 SSE 长连接复核，不再维护会话 Port 与转发 Service。
 */
@Service
public class SessionService implements AuthService {

    private final AccountService accounts;

    /** @param accounts 服务端固定账号服务 */
    public SessionService(AccountService accounts) {
        this.accounts = accounts;
    }

    /** @param actor 已通过凭据校验的身份 */
    public void establish(AuthenticatedActor actor) {
        StpUtil.login(actor.username());
    }

    /** @return 当前身份；非 Web 线程或无效会话返回空 */
    @Override
    public Optional<AuthenticatedActor> current() {
        try {
            if (!StpUtil.isLogin()) {
                return Optional.empty();
            }
            return accounts.findByUsername(StpUtil.getLoginIdAsString())
                    .map(account -> new AuthenticatedActor(
                            account.username(), account.displayName(), account.role()));
        } catch (SaTokenContextException exception) {
            return Optional.empty();
        }
    }

    /** @return 当前有效身份 @throws LoginRequiredException 未登录 */
    @Override
    public AuthenticatedActor currentSession() {
        return current().orElseThrow(LoginRequiredException::new);
    }

    /** 幂等退出当前会话。 */
    public void logout() {
        try {
            if (StpUtil.isLogin()) {
                StpUtil.logout();
            }
        } catch (SaTokenContextException ignored) {
            // 没有 Web 上下文等价于没有需要清理的浏览器会话。
        }
    }

    /** @return 不公开 Token 内容的 SSE 会话租约 @throws LoginRequiredException 未登录 */
    @Override
    public AuthService.SessionLease capture() {
        String tokenValue;
        try {
            tokenValue = StpUtil.getTokenValue();
        } catch (SaTokenContextException exception) {
            throw new LoginRequiredException();
        }
        if (tokenValue == null || tokenValue.isBlank() || current().isEmpty()) {
            throw new LoginRequiredException();
        }
        return new SaTokenSessionLease(tokenValue);
    }

    /** @return 租约仍属于预期账号时为 true */
    @Override
    public boolean isValid(AuthService.SessionLease lease, String expectedUsername) {
        if (!(lease instanceof SaTokenSessionLease sessionLease)
                || expectedUsername == null || expectedUsername.isBlank()) {
            return false;
        }
        try {
            Object loginId = StpUtil.getStpLogic().getLoginIdByToken(sessionLease.tokenValue);
            return loginId != null && expectedUsername.equals(loginId.toString())
                    && accounts.findByUsername(expectedUsername).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * SSE 线程间传递的私有会话能力。Token 不提供访问器，`toString` 也不会泄露内容。
     */
    private static final class SaTokenSessionLease implements AuthService.SessionLease {
        private final String tokenValue;

        private SaTokenSessionLease(String tokenValue) {
            this.tokenValue = tokenValue;
        }

        @Override
        public String toString() {
            return "SessionLease[opaque]";
        }
    }
}
