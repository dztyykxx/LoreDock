package io.github.loredock.identity.infrastructure.config;

import io.github.loredock.identity.domain.WebRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredFixedAccountDirectoryTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 业务目的：生产就绪要求固定目录恰好包含一个管理员和一个共享成员，防止权限账号缺失或角色配错后继续启动。
     */
    @Test
    void validAdminAndMemberConfigurationBuildsDirectory() {
        ConfiguredFixedAccountDirectory directory = new ConfiguredFixedAccountDirectory(validProperties());

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

        assertThatThrownBy(() -> new ConfiguredFixedAccountDirectory(properties))
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

        assertThatThrownBy(() -> new ConfiguredFixedAccountDirectory(properties))
                .isInstanceOf(IdentityConfigurationException.class)
                .hasMessageNotContaining(adminHash)
                .hasMessageNotContaining(memberHash);
    }

    /**
     * 业务目的：账号目录缺少任一固定角色时不能进入就绪状态，防止部署后无法满足管理员或只读入口。
     */
    @Test
    void missingRequiredRoleRejectsReadiness() {
        FixedAccountsProperties properties = new FixedAccountsProperties(List.of(
                account("admin-one", WebRole.ADMIN, encoder.encode("one")),
                account("admin-two", WebRole.ADMIN, encoder.encode("two"))
        ));

        assertThatThrownBy(() -> new ConfiguredFixedAccountDirectory(properties))
                .isInstanceOf(IdentityConfigurationException.class);
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
