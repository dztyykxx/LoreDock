package io.github.loredock.auth.controller;

import io.github.loredock.auth.model.AuthenticatedActor;
import io.github.loredock.auth.model.command.LoginCommand;
import io.github.loredock.auth.model.request.LoginRequest;
import io.github.loredock.auth.model.response.SessionResponse;
import io.github.loredock.auth.service.AccountService;
import io.github.loredock.auth.service.SessionService;
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

    private final AccountService accounts;
    private final SessionService sessions;

    /**
     * @param accounts 固定账号服务
     * @param sessions Web 会话服务
     */
    public AuthController(AccountService accounts, SessionService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    /**
     * 校验固定账号并建立当前浏览器会话。错误账号与错误密码都返回相同 401 语义。
     *
     * @param request 登录请求
     * @return 不含会话令牌或密码哈希的身份摘要
     */
    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedActor actor = accounts.login(new LoginCommand(request.username(), request.password()));
        sessions.establish(actor);
        return response(actor);
    }

    /**
     * @return 当前有效 Web 会话身份；无效、过期或应用重启后的旧 Cookie 返回 401
     */
    @GetMapping("/session")
    public SessionResponse session() {
        return response(sessions.currentSession());
    }

    /**
     * 幂等退出当前浏览器会话，不影响其他 Cookie 对应的会话。
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        sessions.logout();
    }

    private SessionResponse response(AuthenticatedActor actor) {
        return new SessionResponse(actor.username(), actor.displayName(), actor.role());
    }
}
