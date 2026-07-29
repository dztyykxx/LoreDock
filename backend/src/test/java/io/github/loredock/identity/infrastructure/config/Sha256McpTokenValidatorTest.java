package io.github.loredock.identity.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sha256McpTokenValidatorTest {

    /**
     * 业务目的：配置只能保存严格的小写 SHA-256 摘要，防止误把原始共享 Token 或畸形值作为服务凭据启动。
     */
    @Test
    void missingOrMalformedDigestRejectsReadinessWithoutLeakingValue() {
        for (String digest : new String[]{null, "", "raw-shared-token", "ABCDEF", "0".repeat(63)}) {
            assertThatThrownBy(() -> new Sha256McpTokenValidator(new McpTokenProperties(digest)))
                    .isInstanceOf(IdentityConfigurationException.class)
                    .hasMessage("identity configuration invalid: mcp token digest");
        }
    }

    /**
     * 业务目的：只有摘要匹配的高熵 Token 才能进入 MCP 处理链，错误或空 Token 必须被拒绝。
     */
    @Test
    void onlyTokenMatchingConfiguredDigestIsValid() {
        String rawToken = "test-high-entropy-token-4bc656a8";
        Sha256McpTokenValidator validator =
                new Sha256McpTokenValidator(new McpTokenProperties(sha256(rawToken)));

        assertThat(validator.isValid(rawToken)).isTrue();
        assertThat(validator.isValid("wrong-token")).isFalse();
        assertThat(validator.isValid("")).isFalse();
        assertThat(validator.isValid(null)).isFalse();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
