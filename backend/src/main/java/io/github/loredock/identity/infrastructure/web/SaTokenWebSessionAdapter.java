package io.github.loredock.identity.infrastructure.web;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import io.github.loredock.identity.application.FixedAccount;
import io.github.loredock.identity.application.FixedAccountDirectory;
import io.github.loredock.identity.application.WebSessionPort;
import io.github.loredock.identity.domain.AuthenticatedActor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于 Sa-Token 单实例内存 DAO 的 Web 会话适配器。会话只保存稳定账号标识，角色与展示名每次从服务端固定目录解析。
 */
@Component
public class SaTokenWebSessionAdapter implements WebSessionPort {

    private final FixedAccountDirectory accountDirectory;

    /**
     * @param accountDirectory 服务端固定账号目录
     */
    public SaTokenWebSessionAdapter(FixedAccountDirectory accountDirectory) {
        this.accountDirectory = accountDirectory;
    }

    @Override
    public void establish(AuthenticatedActor actor) {
        // 客户端角色永不写入会话；稳定 username 是 Sa-Token loginId，后续角色始终回查受控目录。
        StpUtil.login(actor.username());
    }

    @Override
    public Optional<AuthenticatedActor> current() {
        try {
            if (!StpUtil.isLogin()) {
                return Optional.empty();
            }
            String username = StpUtil.getLoginIdAsString();
            return accountDirectory.findByUsername(username).map(this::toActor);
        } catch (SaTokenContextException exception) {
            // 启动恢复和后台线程不存在 Sa-Token Web 上下文；此时明确视为无人工身份，由审计层记录 SYSTEM。
            return Optional.empty();
        }
    }

    @Override
    public void clear() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    private AuthenticatedActor toActor(FixedAccount account) {
        return new AuthenticatedActor(account.username(), account.displayName(), account.role());
    }
}
