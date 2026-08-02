package io.github.loredock.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.github.loredock.platform.web.McpRequestAccess;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class McpWebConfigurationTest {

    private final McpWebConfiguration configuration =
            new McpWebConfiguration(new McpAccessProperties("read-secret", "write-secret"));

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 业务目的：MCP 不能匿名访问，错误 Token 也不能从 HTTP 层进入工具分发；
     * 防止内部知识工具因独立于 Web Cookie 而意外裸露。
     */
    @Test
    void rejectsMissingOrInvalidBearerTokenBeforeToolDispatch() throws Exception {
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        boolean missing = configuration.authenticationInterceptor()
                .preHandle(new MockHttpServletRequest(), missingResponse, new Object());
        MockHttpServletRequest invalidRequest = new MockHttpServletRequest();
        invalidRequest.addHeader("Authorization", "Bearer wrong-secret");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        boolean invalid = configuration.authenticationInterceptor()
                .preHandle(invalidRequest, invalidResponse, new Object());

        assertThat(missing).isFalse();
        assertThat(missingResponse.getStatus()).isEqualTo(401);
        assertThat(invalid).isFalse();
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
        System.out.println("MCP 鉴权测试证据：missingStatus=" + missingResponse.getStatus()
                + "，invalidStatus=" + invalidResponse.getStatus());
    }

    /**
     * 业务目的：只读 Token 可以连接查询但绝不能提交草稿，写 Token 才取得追加式草稿权限；
     * 防止查询凭据被本地 Agent 用来修改知识状态。
     */
    @Test
    void separatesReadAndWriteTokenCapabilities() throws Exception {
        MockHttpServletRequest readRequest = authenticated("read-secret");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(readRequest));
        assertThatIllegalStateException().isThrownBy(McpRequestAccess::requireWrite)
                .withMessage("MCP_WRITE_TOKEN_REQUIRED");

        MockHttpServletRequest writeRequest = authenticated("write-secret");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(writeRequest));
        McpRequestAccess.requireWrite();

        System.out.println("MCP 权限测试证据：readCanWrite=false，writeCanWrite=true");
    }

    private MockHttpServletRequest authenticated(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        boolean allowed = configuration.authenticationInterceptor()
                .preHandle(request, new MockHttpServletResponse(), new Object());
        assertThat(allowed).isTrue();
        return request;
    }
}
