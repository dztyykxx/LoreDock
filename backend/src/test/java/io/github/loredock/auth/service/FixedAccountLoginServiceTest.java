package io.github.loredock.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.auth.config.FixedAccountProperties;
import io.github.loredock.auth.config.FixedAccountsProperties;
import io.github.loredock.auth.exception.InvalidCredentialsException;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.auth.model.command.LoginCommand;
import io.github.loredock.auth.api.WebRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class FixedAccountLoginServiceTest {

    /**
     * 业务目的：保护管理员和共享成员都只能从服务端配置获得各自角色，防止客户端角色声明进入认证结果。
     */
    @Test
    void configuredAdminAndMemberAuthenticateWithServerRoles() {
        AccountService service = accountService();

        AuthenticatedActor admin = service.login(new LoginCommand("admin", "admin-password"));
        AuthenticatedActor member = service.login(new LoginCommand("member", "member-password"));

        assertThat(admin).isEqualTo(new AuthenticatedActor("admin", "管理员", WebRole.ADMIN));
        assertThat(member).isEqualTo(new AuthenticatedActor("member", "组内成员", WebRole.MEMBER));
    }

    /**
     * 业务目的：未知账号与错误密码必须保持完全相同的失败类型和消息，防止调用方枚举固定账号。
     */
    @Test
    void unknownUsernameAndWrongPasswordHaveSameFailureAppearance() {
        AccountService service = accountService();

        Throwable unknown = catchFailure(service, new LoginCommand("unknown", "guess"));
        Throwable wrongPassword = catchFailure(service, new LoginCommand("admin", "guess"));

        assertThat(unknown).isExactlyInstanceOf(InvalidCredentialsException.class);
        assertThat(wrongPassword).isExactlyInstanceOf(InvalidCredentialsException.class);
        assertThat(unknown.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    /**
     * 业务目的：认证失败对象不得携带提交密码或已配置哈希，防止统一异常后续进入日志时泄露敏感值。
     */
    @Test
    void authenticationFailureDoesNotContainPasswordOrHash() {
        AccountService service = accountService();

        assertThatThrownBy(() -> service.login(new LoginCommand("admin", "submitted-secret")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageNotContaining("submitted-secret")
                .hasMessageNotContaining("admin-password");
    }

    private AccountService accountService() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        FixedAccountsProperties properties = new FixedAccountsProperties(List.of(
                new FixedAccountProperties(
                        "admin", "管理员", WebRole.ADMIN, encoder.encode("admin-password")),
                new FixedAccountProperties(
                        "member", "组内成员", WebRole.MEMBER, encoder.encode("member-password"))
        ));
        return new AccountService(properties, encoder);
    }

    private Throwable catchFailure(AccountService service, LoginCommand command) {
        try {
            service.login(command);
            throw new AssertionError("expected login failure");
        } catch (InvalidCredentialsException exception) {
            return exception;
        }
    }
}
