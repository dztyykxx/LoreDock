package io.github.loredock.identity.infrastructure.config;

import io.github.loredock.identity.application.McpTokenValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 使用 SHA-256 摘要校验 MCP 高熵共享 Token。
 *
 * <p>机器 Token 由足够随机性提供抗猜测能力，因此使用确定性摘要便于服务端比较；它不同于低熵的人类密码，
 * 后者仍必须交由 BCrypt 的盐和工作因子保护。配置阶段即拒绝原值或畸形摘要，避免服务带错误凭据就绪。</p>
 */
public final class Sha256McpTokenValidator implements McpTokenValidator {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private final byte[] expectedDigest;

    /**
     * @param properties 只含摘要的 MCP 配置
     * @throws IdentityConfigurationException 摘要缺失或格式不合法
     */
    public Sha256McpTokenValidator(McpTokenProperties properties) {
        String digest = properties == null ? null : properties.tokenSha256();
        if (digest == null || !SHA256_HEX.matcher(digest).matches()) {
            throw new IdentityConfigurationException("mcp token digest");
        }
        this.expectedDigest = HexFormat.of().parseHex(digest);
    }

    @Override
    public boolean isValid(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) {
            return false;
        }
        byte[] actualDigest = sha256(rawToken);
        return MessageDigest.isEqual(expectedDigest, actualDigest);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }
}
