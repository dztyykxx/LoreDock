package io.github.loredock.identity.infrastructure.web;

import io.github.loredock.identity.application.CurrentSessionUseCase;
import io.github.loredock.identity.application.LoginCommand;
import io.github.loredock.identity.application.LoginUseCase;
import io.github.loredock.identity.application.LogoutUseCase;
import io.github.loredock.identity.application.WebSessionPort;
import io.github.loredock.identity.domain.AuthenticatedActor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 固定账号认证入口。Controller 只编排凭据用例与会话端口，不读取客户端角色或返回任何 Token/哈希。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final WebSessionPort sessionPort;
    private final CurrentSessionUseCase currentSessionUseCase;
    private final LogoutUseCase logoutUseCase;

    /**
     * @param loginUseCase 固定账号凭据校验
     * @param sessionPort Web 会话建立端口
     * @param currentSessionUseCase 当前会话查询
     * @param logoutUseCase 幂等退出用例
     */
    public AuthController(
            LoginUseCase loginUseCase,
            WebSessionPort sessionPort,
            CurrentSessionUseCase currentSessionUseCase,
            LogoutUseCase logoutUseCase
    ) {
        this.loginUseCase = loginUseCase;
        this.sessionPort = sessionPort;
        this.currentSessionUseCase = currentSessionUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    /**
     * 校验固定账号并建立当前浏览器会话。错误账号与错误密码都返回相同 401 语义。
     *
     * @param request 登录请求
     * @return 不含会话令牌或密码哈希的身份摘要
     */
    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedActor actor = loginUseCase.login(new LoginCommand(request.username(), request.password()));
        sessionPort.establish(actor);
        return response(actor);
    }

    /**
     * @return 当前有效 Web 会话身份；无效、过期或应用重启后的旧 Cookie 返回 401
     */
    @GetMapping("/session")
    public SessionResponse session() {
        return response(currentSessionUseCase.currentSession());
    }

    /**
     * 幂等退出当前浏览器会话，不影响其他 Cookie 对应的会话。
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        logoutUseCase.logout();
    }

    private SessionResponse response(AuthenticatedActor actor) {
        return new SessionResponse(actor.username(), actor.displayName(), actor.role());
    }
}
