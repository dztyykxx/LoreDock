package io.github.loredock.identity.application;

import io.github.loredock.identity.domain.AuthenticatedActor;
import io.github.loredock.identity.domain.WebRole;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedAccountLoginServiceTest {

    private static final FixedAccount ADMIN =
            new FixedAccount("admin", "管理员", WebRole.ADMIN, "admin-hash");
    private static final FixedAccount MEMBER =
            new FixedAccount("member", "组内成员", WebRole.MEMBER, "member-hash");

    /**
     * 业务目的：保护管理员和共享成员都只能从服务端目录获得各自角色，防止客户端角色声明进入认证结果。
     */
    @Test
    void configuredAdminAndMemberAuthenticateWithServerRoles() {
        FixedAccountLoginService service = new FixedAccountLoginService(
                new StubDirectory(List.of(ADMIN, MEMBER)),
                (rawPassword, hash) -> (rawPassword + "-hash").equals(hash)
        );

        AuthenticatedActor admin = service.login(new LoginCommand("admin", "admin"));
        AuthenticatedActor member = service.login(new LoginCommand("member", "member"));

        assertThat(admin).isEqualTo(new AuthenticatedActor("admin", "管理员", WebRole.ADMIN));
        assertThat(member).isEqualTo(new AuthenticatedActor("member", "组内成员", WebRole.MEMBER));
    }

    /**
     * 业务目的：未知账号与错误密码必须保持完全相同的失败类型和消息，防止调用方枚举固定账号。
     */
    @Test
    void unknownUsernameAndWrongPasswordHaveSameFailureAppearance() {
        FixedAccountLoginService service = new FixedAccountLoginService(
                new StubDirectory(List.of(ADMIN, MEMBER)),
                (rawPassword, hash) -> false
        );

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
        FixedAccountLoginService service = new FixedAccountLoginService(
                new StubDirectory(List.of(ADMIN, MEMBER)),
                (rawPassword, hash) -> false
        );

        assertThatThrownBy(() -> service.login(new LoginCommand("admin", "submitted-secret")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageNotContaining("submitted-secret")
                .hasMessageNotContaining("admin-hash");
    }

    private Throwable catchFailure(FixedAccountLoginService service, LoginCommand command) {
        try {
            service.login(command);
            throw new AssertionError("expected login failure");
        } catch (InvalidCredentialsException exception) {
            return exception;
        }
    }

    private record StubDirectory(List<FixedAccount> accounts) implements FixedAccountDirectory {

        @Override
        public Optional<FixedAccount> findByUsername(String username) {
            return accounts.stream().filter(account -> account.username().equals(username)).findFirst();
        }

        @Override
        public Collection<FixedAccount> configuredAccounts() {
            return List.copyOf(accounts);
        }
    }
}
