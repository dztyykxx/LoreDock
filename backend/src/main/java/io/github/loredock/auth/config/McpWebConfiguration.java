package io.github.loredock.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.github.loredock.platform.web.McpRequestAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MCP Streamable HTTP 的独立 Token 边界；不复用浏览器 Cookie 会话。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpAccessProperties.class)
public class McpWebConfiguration implements WebMvcConfigurer {

    private final McpAccessProperties properties;

    /** @param properties 部署环境注入的独立读写 Token */
    public McpWebConfiguration(McpAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor()).addPathPatterns("/mcp", "/mcp/**").order(-10);
    }

    HandlerInterceptor authenticationInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Access access = resolve(request.getHeader("Authorization"));
                if (access == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                McpRequestAccess.mark(request, access == Access.WRITE);
                return true;
            }
        };
    }

    private Access resolve(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length());
        if (matches(token, properties.writeToken())) {
            return Access.WRITE;
        }
        return matches(token, properties.readToken()) ? Access.READ : null;
    }

    private boolean matches(String actual, String expected) {
        if (actual == null || actual.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private enum Access { READ, WRITE }
}
