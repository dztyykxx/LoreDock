package io.github.loredock.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 的独立读写 Token。空值表示对应能力未配置，不会退化为匿名访问。
 *
 * @param readToken 只读查询 Token
 * @param writeToken 包含查询和草稿提交权限的写 Token
 */
@ConfigurationProperties("loredock.mcp")
public record McpAccessProperties(String readToken, String writeToken) {
}
