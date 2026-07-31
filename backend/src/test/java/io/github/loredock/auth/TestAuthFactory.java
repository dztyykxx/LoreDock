package io.github.loredock.auth;

import io.github.loredock.auth.config.FixedAccountProperties;
import io.github.loredock.auth.config.FixedAccountsProperties;
import io.github.loredock.auth.api.WebRole;
import io.github.loredock.auth.service.AccountService;
import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 为 Web 契约测试提供无敏感信息的固定 ADMIN/MEMBER 账号。 */
public final class TestAuthFactory {

    private static final String CORRECT_PASSWORD_HASH =
            "$2y$04$MlLceZRhiI9byGjXsTgLUu2myt5fs1C351Coxmc.VHByHs2GqOYSG";

    private TestAuthFactory() {
    }

    /** @return 密码均为 `correct-password` 的测试账号服务 */
    public static AccountService accountService() {
        return new AccountService(new FixedAccountsProperties(List.of(
                new FixedAccountProperties("admin", "管理员", WebRole.ADMIN, CORRECT_PASSWORD_HASH),
                new FixedAccountProperties("member", "组内成员", WebRole.MEMBER, CORRECT_PASSWORD_HASH)
        )), new BCryptPasswordEncoder());
    }
}
