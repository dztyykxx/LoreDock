package io.github.loredock.identity.infrastructure.web;

import io.github.loredock.identity.application.CurrentSessionUseCase;
import io.github.loredock.identity.application.LoginRequiredException;
import io.github.loredock.identity.application.LogoutUseCase;
import io.github.loredock.identity.application.WebSessionPort;
import io.github.loredock.identity.domain.AuthenticatedActor;
import org.springframework.stereotype.Service;

/**
 * Web 会话应用实现。查询把无效或重启后丢失的会话统一视为未登录；退出保持幂等并只操作当前请求会话。
 */
@Service
public class WebSessionService implements CurrentSessionUseCase, LogoutUseCase {

    private final WebSessionPort sessionPort;

    /**
     * @param sessionPort Sa-Token 会话端口
     */
    public WebSessionService(WebSessionPort sessionPort) {
        this.sessionPort = sessionPort;
    }

    @Override
    public AuthenticatedActor currentSession() {
        return sessionPort.current().orElseThrow(LoginRequiredException::new);
    }

    @Override
    public void logout() {
        sessionPort.clear();
    }
}
