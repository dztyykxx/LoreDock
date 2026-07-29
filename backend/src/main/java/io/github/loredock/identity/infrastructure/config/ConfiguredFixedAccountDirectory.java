package io.github.loredock.identity.infrastructure.config;

import io.github.loredock.identity.application.FixedAccount;
import io.github.loredock.identity.application.FixedAccountDirectory;
import io.github.loredock.identity.domain.WebRole;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从强类型配置构建不可变固定账号目录。构造阶段完成全部就绪校验，避免服务以缺失账号、重复身份或非 BCrypt 值运行。
 */
public class ConfiguredFixedAccountDirectory implements FixedAccountDirectory {

    private static final Pattern BCRYPT_PATTERN =
            Pattern.compile("^\\$2[ayb]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}$");

    private final Map<String, FixedAccount> accountsByUsername;
    private final List<FixedAccount> accounts;

    /**
     * 校验并冻结固定账号配置。失败消息不会包含账号、显示名或密码哈希。
     *
     * @param properties 强类型账号配置
     * @throws IdentityConfigurationException 账号数量、角色、标识或 BCrypt 格式不符合就绪要求
     */
    public ConfiguredFixedAccountDirectory(FixedAccountsProperties properties) {
        if (properties == null || properties.accounts() == null || properties.accounts().size() != 2) {
            throw new IdentityConfigurationException("required account count");
        }

        Map<String, FixedAccount> mutableAccounts = new LinkedHashMap<>();
        EnumSet<WebRole> roles = EnumSet.noneOf(WebRole.class);
        for (FixedAccountProperties account : properties.accounts()) {
            validateAccount(account);
            FixedAccount fixedAccount = new FixedAccount(
                    account.username(), account.displayName(), account.role(), account.passwordHash());
            if (mutableAccounts.putIfAbsent(account.username(), fixedAccount) != null) {
                throw new IdentityConfigurationException("duplicate username");
            }
            if (!roles.add(account.role())) {
                throw new IdentityConfigurationException("duplicate role");
            }
        }
        if (!roles.equals(EnumSet.of(WebRole.ADMIN, WebRole.MEMBER))) {
            throw new IdentityConfigurationException("required roles");
        }

        this.accountsByUsername = Map.copyOf(mutableAccounts);
        this.accounts = List.copyOf(mutableAccounts.values());
    }

    @Override
    public Optional<FixedAccount> findByUsername(String username) {
        return Optional.ofNullable(username).map(accountsByUsername::get);
    }

    @Override
    public Collection<FixedAccount> configuredAccounts() {
        return accounts;
    }

    private void validateAccount(FixedAccountProperties account) {
        if (account == null
                || isBlank(account.username())
                || isBlank(account.displayName())
                || account.role() == null
                || account.passwordHash() == null
                || !BCRYPT_PATTERN.matcher(account.passwordHash()).matches()) {
            throw new IdentityConfigurationException("account fields");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
