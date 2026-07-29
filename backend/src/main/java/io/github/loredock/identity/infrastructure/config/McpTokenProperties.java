package io.github.loredock.identity.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 机器凭据配置。配置中只允许保存原始高熵 Token 的 SHA-256 摘要。
 *
 * @param tokenSha256 小写十六进制 SHA-256 摘要
 */
@ConfigurationProperties("loredock.identity.mcp")
public record McpTokenProperties(String tokenSha256) {
}
