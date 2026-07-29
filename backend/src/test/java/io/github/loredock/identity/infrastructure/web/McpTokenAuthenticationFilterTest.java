package io.github.loredock.identity.infrastructure.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.loredock.identity.application.McpTokenValidator;
import io.github.loredock.platform.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.Cookie;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenAuthenticationFilterTest {

    private static final String VALID_TOKEN = "test-valid-mcp-token-9f59f17e";
    private static final String CONFIGURED_DIGEST = "a".repeat(64);
    private final TimeProvider timeProvider = () -> Instant.parse("2026-07-29T12:00:00Z");

    /**
     * 业务目的：缺失、格式错误或错误的 Bearer Token 必须在 MCP 业务分派前统一返回 401。
     */
    @Test
    void missingMalformedAndWrongBearerTokenAreRejectedBeforeDispatch() throws Exception {
        McpTokenAuthenticationFilter filter = filter(token -> VALID_TOKEN.equals(token));

        for (String authorization : new String[]{null, "", "Basic abc", "Bearer", "Bearer ", "Bearer wrong token", "bearer " + VALID_TOKEN}) {
            MockHttpServletRequest request = mcpRequest();
            if (authorization != null) {
                request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("MCP_TOKEN_INVALID");
            assertThat(chain.getRequest()).isNull();
        }
    }

    /**
     * 业务目的：管理员 Web Cookie 不能替代 MCP Token，防止两种身份边界互相提权。
     */
    @Test
    void webSessionCookieCannotReplaceMcpToken() throws Exception {
        McpTokenAuthenticationFilter filter = filter(token -> VALID_TOKEN.equals(token));
        MockHttpServletRequest request = mcpRequest();
        request.setCookies(new Cookie("loredock_session", "administrator-web-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * 业务目的：有效 Token 只能建立只读机器请求标记并继续过滤链，不得创建 Web 会话或管理员角色。
     */
    @Test
    void validTokenContinuesChainWithReadOnlyMachineIdentity() throws Exception {
        McpTokenAuthenticationFilter filter = filter(token -> VALID_TOKEN.equals(token));
        MockHttpServletRequest request = mcpRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(McpTokenAuthenticationFilter.MCP_IDENTITY_ATTRIBUTE))
                .isEqualTo("MCP_READ_ONLY");
        assertThat(response.getCookie("loredock_session")).isNull();
    }

    /**
     * 业务目的：错误 Token 的安全日志只能记录失败分类，不能包含来值或配置摘要，防止日志成为凭据副本。
     */
    @Test
    void invalidTokenLogDoesNotContainRawValueOrConfiguredDigest() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(McpTokenAuthenticationFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            McpTokenAuthenticationFilter filter = filter(token -> false);
            MockHttpServletRequest request = mcpRequest();
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer raw-secret-token");

            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .doesNotContain("raw-secret-token")
                        .doesNotContain(CONFIGURED_DIGEST);
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 业务目的：MCP Token 过滤器不得拦截或认证普通 Web 路径，机器凭据不能因此获得管理接口身份。
     */
    @Test
    void filterDoesNotTreatMcpTokenAsWebAdminIdentity() throws Exception {
        McpTokenAuthenticationFilter filter = filter(token -> true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/projects");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(McpTokenAuthenticationFilter.MCP_IDENTITY_ATTRIBUTE)).isNull();
    }

    private McpTokenAuthenticationFilter filter(McpTokenValidator validator) {
        return new McpTokenAuthenticationFilter(
                validator,
                new SecurityErrorWriter(JsonMapper.builder().build(), timeProvider)
        );
    }

    private MockHttpServletRequest mcpRequest() {
        return new MockHttpServletRequest("POST", "/mcp/tools");
    }
}
