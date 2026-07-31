package io.github.loredock.auth.config;

import io.github.loredock.auth.service.AccountService;
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

    /** @param properties 固定账号配置 @param encoder BCrypt 编码器 @return 账号服务 */
    @Bean
    public AccountService accountService(FixedAccountsProperties properties, BCryptPasswordEncoder encoder) {
        return new AccountService(properties, encoder);
    }
}
