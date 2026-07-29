package io.github.loredock.identity.application;

import io.github.loredock.identity.domain.AuthenticatedActor;

/**
 * 固定账号登录实现。账号目录是唯一角色事实来源；所有凭据失败使用统一异常，且未知账号也执行一次 BCrypt 等价校验以缩小账号枚举差异。
 */
public class FixedAccountLoginService implements LoginUseCase {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final FixedAccountDirectory accountDirectory;
    private final PasswordVerifier passwordVerifier;

    /**
     * @param accountDirectory 服务端固定账号目录
     * @param passwordVerifier 成熟 BCrypt 库适配器
     */
    public FixedAccountLoginService(
            FixedAccountDirectory accountDirectory,
            PasswordVerifier passwordVerifier
    ) {
        this.accountDirectory = accountDirectory;
        this.passwordVerifier = passwordVerifier;
    }

    @Override
    public AuthenticatedActor login(LoginCommand command) {
        if (command == null) {
            throw invalidAfterDummyVerification(null);
        }
        FixedAccount account = accountDirectory.findByUsername(command.username()).orElse(null);
        if (account == null) {
            throw invalidAfterDummyVerification(command.password());
        }
        if (!passwordVerifier.matches(command.password(), account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return new AuthenticatedActor(account.username(), account.displayName(), account.role());
    }

    private InvalidCredentialsException invalidAfterDummyVerification(String rawPassword) {
        // 未知账号仍走相同 BCrypt 计算路径，且丢弃结果，避免用“是否执行哈希”形成明显的账号存在性旁路。
        passwordVerifier.matches(rawPassword == null ? "" : rawPassword, DUMMY_BCRYPT_HASH);
        return new InvalidCredentialsException();
    }
}
