package io.github.loredock.auth.service;

import cn.dev33.satoken.exception.NotWebContextException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 认证感知审计操作者实现。有效 Web 请求记录稳定账号标识；后台、启动恢复和请求清理后的机器工作明确记录 SYSTEM。
 */
@Component("auditActorSupplier")
public class AuthenticatedActorService implements Supplier<String> {

    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final SessionService sessions;

    /**
     * @param sessionPort 当前 Web 会话端口
     */
    public AuthenticatedActorService(SessionService sessions) {
        this.sessions = sessions;
    }

    public String currentActor() {
        try {
            return sessions.current()
                    .map(actor -> actor.username())
                    .orElse(SYSTEM_ACTOR);
        } catch (NotWebContextException exception) {
            // 后台与启动线程没有 Servlet 请求上下文；它们必须保留机器身份，不能冒充最近一次人工会话。
            return SYSTEM_ACTOR;
        }
    }

    @Override
    public String get() {
        return currentActor();
    }
}
