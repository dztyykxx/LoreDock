package io.github.loredock.identity.infrastructure.web;

import io.github.loredock.identity.application.McpTokenValidator;
import io.github.loredock.identity.infrastructure.config.McpTokenProperties;
import io.github.loredock.identity.infrastructure.config.Sha256McpTokenValidator;
import io.github.loredock.platform.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 组装独立 MCP 机器身份边界，不向 Sa-Token Web 会话授予或读取任何角色。
 */
@Configuration(proxyBeanMethods = false)
public class McpSecurityConfiguration {

    /** @param properties 仅含摘要的配置 @return MCP Token 校验器 */
    @Bean
    public McpTokenValidator mcpTokenValidator(McpTokenProperties properties) {
        return new Sha256McpTokenValidator(properties);
    }

    /** @param jsonMapper JSON 序列化器 @param timeProvider UTC 时间端口 @return 安全错误写出器 */
    @Bean
    public SecurityErrorWriter securityErrorWriter(JsonMapper jsonMapper, TimeProvider timeProvider) {
        return new SecurityErrorWriter(jsonMapper, timeProvider);
    }

    /**
     * @param validator MCP Token 校验器
     * @param errorWriter 安全错误写出器
     * @return 在 MCP 分派前执行的认证过滤器
     */
    @Bean
    public McpTokenAuthenticationFilter mcpTokenAuthenticationFilter(
            McpTokenValidator validator,
            SecurityErrorWriter errorWriter
    ) {
        return new McpTokenAuthenticationFilter(validator, errorWriter);
    }
}
