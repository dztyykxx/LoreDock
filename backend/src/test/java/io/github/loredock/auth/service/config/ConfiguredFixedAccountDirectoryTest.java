package io.github.loredock.auth.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.auth.config.FixedAccountProperties;
import io.github.loredock.auth.config.FixedAccountsProperties;
import io.github.loredock.auth.exception.IdentityConfigurationException;
import io.github.loredock.auth.api.WebRole;
import io.github.loredock.auth.service.AccountService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ConfiguredFixedAccountDirectoryTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 业务目的：管理员加共享只读成员的双账号配置仍然合法，角色代码保留双角色语义。
     */
    @Test
    void validAdminAndMemberConfigurationBuildsDirectory() {
        AccountService directory = new AccountService(validProperties(), encoder);

        assertThat(directory.configuredAccounts())
                .extracting(account -> account.role())
                .containsExactlyInAnyOrder(WebRole.ADMIN, WebRole.MEMBER);
    }

    /**
     * 业务目的：缺失或非 BCrypt 哈希必须拒绝就绪，防止明文、弱摘要或空密码配置被当作可登录凭据。
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"plain-password", "$2a$10$short", "{bcrypt}$2a$10$invalid"})
    void missingOrMalformedBcryptHashRejectsReadiness(String passwordHash) {
        FixedAccountsProperties properties = new FixedAccountsProperties(List.of(
                account("admin", WebRole.ADMIN, passwordHash),
                account("member", WebRole.MEMBER, encoder.encode("member-secret"))
        ));

        assertThatThrownBy(() -> new AccountService(properties, encoder))
                .isInstanceOf(IdentityConfigurationException.class)
                .hasMessage("identity configuration invalid: account fields");
    }

    /**
     * 业务目的：账号标识重复会让角色解析和审计身份产生歧义，必须在服务就绪前被拒绝。
     */
    @Test
    void duplicateUsernameRejectsReadinessWithoutLeakingHashes() {
        String adminHash = encoder.encode("admin-secret");
        String memberHash = encoder.encode("member-secret");
        FixedAccountsProperties properties = new FixedAccountsProperties(List.of(
                new FixedAccountProperties("shared", "管理员", WebRole.ADMIN, adminHash),
                new FixedAccountProperties("shared", "组内成员", WebRole.MEMBER, memberHash)
        ));

        assertThatThrownBy(() -> new AccountService(properties, encoder))
                .isInstanceOf(IdentityConfigurationException.class)
                .hasMessageNotContaining(adminHash)
                .hasMessageNotContaining(memberHash);
    }

    /**
     * 业务目的：账号目录不允许出现两个管理员，角色重复必须在就绪前被拒绝，
     * 防止审计身份和授权判定产生歧义。
     */
    @Test
    void duplicateAdminRoleRejectsReadiness() {
        FixedAccountsProperties properties = new FixedAccountsProperties(List.of(
                account("admin-one", WebRole.ADMIN, encoder.encode("one")),
                account("admin-two", WebRole.ADMIN, encoder.encode("two"))
        ));

        assertThatThrownBy(() -> new AccountService(properties, encoder))
                .isInstanceOf(IdentityConfigurationException.class);
    }

    /**
     * 业务目的：单管理员部署只配置一个 ADMIN 账号也必须就绪，MEMBER 只读账号是可选配置；
     * 防止要求恰好两个账号的旧校验阻止单管理员部署启动。
     */
    @Test
    void adminOnlyConfigurationBuildsDirectory() {
        AccountService directory = new AccountService(new FixedAccountsProperties(List.of(
                account("admin", WebRole.ADMIN, encoder.encode("admin-secret")))), encoder);

        assertThat(directory.configuredAccounts())
                .extracting(account -> account.role())
                .containsExactly(WebRole.ADMIN);
        System.out.printf("测试证据：场景=单管理员配置，账号数=%d，角色=%s%n",
                directory.configuredAccounts().size(),
                directory.configuredAccounts().iterator().next().role());
    }

    private FixedAccountsProperties validProperties() {
        return new FixedAccountsProperties(List.of(
                account("admin", WebRole.ADMIN, encoder.encode("admin-secret")),
                account("member", WebRole.MEMBER, encoder.encode("member-secret"))
        ));
    }

    private FixedAccountProperties account(String username, WebRole role, String passwordHash) {
        return new FixedAccountProperties(username, username + " display", role, passwordHash);
    }
}
