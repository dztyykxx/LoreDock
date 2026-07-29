package io.github.loredock.identity.infrastructure.config;

import io.github.loredock.identity.application.PasswordVerifier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 使用 Spring Security Crypto 的 BCrypt 实现校验人类密码，不自行实现或降级密码哈希算法。
 */
public class BCryptPasswordVerifier implements PasswordVerifier {

    private final BCryptPasswordEncoder encoder;

    /**
     * @param encoder Spring Security Crypto 提供的 BCrypt 编码器
     */
    public BCryptPasswordVerifier(BCryptPasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return rawPassword != null && encoder.matches(rawPassword, passwordHash);
    }
}
