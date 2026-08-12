package io.github.loredock.auth.service;

import io.github.loredock.auth.config.FixedAccount;
import io.github.loredock.auth.config.FixedAccountProperties;
import io.github.loredock.auth.config.FixedAccountsProperties;
import io.github.loredock.auth.exception.IdentityConfigurationException;
import io.github.loredock.auth.exception.InvalidCredentialsException;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.auth.api.WebRole;
import io.github.loredock.auth.model.command.LoginCommand;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 固定账号服务。集中完成配置校验、账号查询和 BCrypt 登录，不再为三项单实现操作拆分目录、校验器和 UseCase。
 */
public class AccountService {

    private static final Pattern BCRYPT_PATTERN =
            Pattern.compile("^\\$2[ayb]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}$");
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final Map<String, FixedAccount> accountsByUsername;
    private final List<FixedAccount> accounts;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * @param properties 固定账号的强类型配置；单管理员部署可以只配置一个 ADMIN，共享只读账号可选
     * @param passwordEncoder Spring Security BCrypt 实现
     * @throws IdentityConfigurationException 账号数量、角色或哈希格式无效
     */
    public AccountService(FixedAccountsProperties properties, BCryptPasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        // 单管理员部署只配置一个 ADMIN 账号，MEMBER 只读账号可选；最多两个账号由角色唯一性兜底。
        if (properties == null || properties.accounts() == null
                || properties.accounts().size() < 1 || properties.accounts().size() > 2) {
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
        // 必须恰好存在一个管理员；MEMBER 只读账号允许缺席，重复角色已在上面拒绝。
        if (!roles.contains(WebRole.ADMIN)) {
            throw new IdentityConfigurationException("required roles");
        }
        this.accountsByUsername = Map.copyOf(mutableAccounts);
        this.accounts = List.copyOf(mutableAccounts.values());
    }

    /**
     * 校验凭据并返回服务端身份。未知账号仍执行 BCrypt，避免明显的账号枚举时间差。
     *
     * @param command 登录输入
     * @return 已认证身份
     * @throws InvalidCredentialsException 账号或密码错误
     */
    public AuthenticatedActor login(LoginCommand command) {
        if (command == null) {
            throw invalidAfterDummyVerification(null);
        }
        FixedAccount account = findByUsername(command.username()).orElse(null);
        if (account == null) {
            throw invalidAfterDummyVerification(command.password());
        }
        if (!passwordEncoder.matches(command.password(), account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return new AuthenticatedActor(account.username(), account.displayName(), account.role());
    }

    /** @param username 稳定账号名 @return 配置中的账号 */
    public Optional<FixedAccount> findByUsername(String username) {
        return Optional.ofNullable(username).map(accountsByUsername::get);
    }

    /** @return 不可变的固定账号集合 */
    public Collection<FixedAccount> configuredAccounts() {
        return accounts;
    }

    private InvalidCredentialsException invalidAfterDummyVerification(String rawPassword) {
        passwordEncoder.matches(rawPassword == null ? "" : rawPassword, DUMMY_BCRYPT_HASH);
        return new InvalidCredentialsException();
    }

    private void validateAccount(FixedAccountProperties account) {
        if (account == null || isBlank(account.username()) || isBlank(account.displayName())
                || account.role() == null || account.passwordHash() == null
                || !BCRYPT_PATTERN.matcher(account.passwordHash()).matches()) {
            throw new IdentityConfigurationException("account fields");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
