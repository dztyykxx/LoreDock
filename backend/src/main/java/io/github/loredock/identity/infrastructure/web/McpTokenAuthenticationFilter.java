package io.github.loredock.identity.infrastructure.web;

import io.github.loredock.identity.application.McpTokenValidator;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.SecurityErrorFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 MCP 请求分派前验证独立的机器 Token；该身份只在当前请求上标记，不创建 Web 会话或角色。
 */
public final class McpTokenAuthenticationFilter extends OncePerRequestFilter {

    /** MCP 下游只读机器身份的请求属性名。 */
    public static final String MCP_IDENTITY_ATTRIBUTE = McpTokenAuthenticationFilter.class.getName() + ".identity";
    private static final String MCP_IDENTITY = "MCP_READ_ONLY";
    private static final Pattern STRICT_BEARER = Pattern.compile("Bearer ([!#$%&'*+.^_`|~0-9A-Za-z-]+)");
    private static final Logger LOGGER = LoggerFactory.getLogger(McpTokenAuthenticationFilter.class);

    private final McpTokenValidator tokenValidator;
    private final SecurityErrorWriter errorWriter;

    /**
     * @param tokenValidator Token 摘要校验端口
     * @param errorWriter MVC 分派前安全错误写出器
     */
    public McpTokenAuthenticationFilter(McpTokenValidator tokenValidator, SecurityErrorWriter errorWriter) {
        this.tokenValidator = tokenValidator;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/mcp") || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String rawToken = strictBearerToken(request);
        if (rawToken == null || !tokenValidator.isValid(rawToken)) {
            // 失败日志只保存分类和 trace ID；Authorization、来值和配置摘要均不得进入参数列表。
            LOGGER.warn("mcp_authentication_failure traceId={} classification=invalid_token",
                    SecurityErrorFactory.traceId());
            errorWriter.write(response, ErrorCode.MCP_TOKEN_INVALID);
            return;
        }
        request.setAttribute(MCP_IDENTITY_ATTRIBUTE, MCP_IDENTITY);
        filterChain.doFilter(request, response);
    }

    private String strictBearerToken(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.size() != 1) {
            return null;
        }
        Matcher matcher = STRICT_BEARER.matcher(values.getFirst());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
