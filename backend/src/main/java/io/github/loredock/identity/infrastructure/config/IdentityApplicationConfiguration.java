package io.github.loredock.identity.infrastructure.config;

import io.github.loredock.identity.application.FixedAccountDirectory;
import io.github.loredock.identity.application.FixedAccountLoginService;
import io.github.loredock.identity.application.LoginUseCase;
import io.github.loredock.identity.application.PasswordVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 组装固定账号登录应用能力；此配置仅接入密码校验，不建立 Sa-Token 会话或 Web 拦截链。
 */
@Configuration(proxyBeanMethods = false)
public class IdentityApplicationConfiguration {

    /** @return Spring Security Crypto 提供的 BCrypt 编码器 */
    @Bean
    public BCryptPasswordEncoder bcryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** @param encoder BCrypt 编码器 @return 密码校验端口实现 */
    @Bean
    public PasswordVerifier passwordVerifier(BCryptPasswordEncoder encoder) {
        return new BCryptPasswordVerifier(encoder);
    }

    /** @param properties 固定账号配置 @return 已完整校验的不可变账号目录 */
    @Bean
    public FixedAccountDirectory fixedAccountDirectory(FixedAccountsProperties properties) {
        return new ConfiguredFixedAccountDirectory(properties);
    }

    /**
     * @param directory 固定账号目录
     * @param passwordVerifier BCrypt 校验端口
     * @return 不建立会话的最小登录用例
     */
    @Bean
    public LoginUseCase loginUseCase(FixedAccountDirectory directory, PasswordVerifier passwordVerifier) {
        return new FixedAccountLoginService(directory, passwordVerifier);
    }
}
