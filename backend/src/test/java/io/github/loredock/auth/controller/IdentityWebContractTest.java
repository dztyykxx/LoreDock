package io.github.loredock.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import io.github.loredock.auth.TestAuthFactory;
import io.github.loredock.auth.config.IdentityRoleConfiguration;
import io.github.loredock.auth.config.IdentityWebConfiguration;
import io.github.loredock.auth.service.AccountService;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = {AuthController.class, IdentityWebContractTest.ProtectedProbeController.class},
        properties = {
                "sa-token.token-name=loredock_session",
                "sa-token.is-read-header=false",
                "sa-token.is-read-body=false",
                "sa-token.is-read-cookie=true",
                "sa-token.is-lasting-cookie=true",
                "sa-token.timeout=1800",
                "sa-token.cookie.path=/",
                "sa-token.cookie.secure=true",
                "sa-token.cookie.http-only=true",
                "sa-token.cookie.same-site=Strict"
        }
)
@Import({
        IdentityWebConfiguration.class,
        IdentityRoleConfiguration.class,
        SessionService.class,

        IdentityWebContractTest.ProtectedProbeController.class,
        IdentityWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class IdentityWebContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProtectedProbeController probeController;

    @BeforeEach
    void resetInMemorySessionsAndProbe() {
        SaTokenDaoDefaultImpl freshSessionStore = new SaTokenDaoDefaultImpl();
        freshSessionStore.init();
        SaManager.setSaTokenDao(freshSessionStore);
        probeController.reset();
    }

    /**
     * 业务目的：登录响应只返回安全身份摘要，并通过同站 HttpOnly Cookie 建立可在刷新后恢复的会话。
     */
    @Test
    void loginCreatesProtectedCookieAndSessionCanBeRestored() throws Exception {
        MvcResult login = login("admin", "correct-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.displayName").value("管理员"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("loredock_session=")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");

        Cookie sessionCookie = login.getResponse().getCookie("loredock_session");
        assertThat(sessionCookie).isNotNull();
        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    /**
     * 业务目的：退出必须幂等且只让当前 Cookie 失效，防止重复操作产生 500 或残留可用会话。
     */
    @Test
    void logoutIsIdempotentAndInvalidatesCurrentSession() throws Exception {
        Cookie sessionCookie = loginCookie("member");

        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
    }

    /**
     * 业务目的：全部受保护 Web API 在缺少会话时统一返回 401，且不得进入任何业务处理。
     */
    @Test
    void protectedApiWithoutSessionReturnsLoginRequired() throws Exception {
        mockMvc.perform(get("/api/projects/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));

        assertThat(probeController.readCount()).isZero();
    }

    /**
     * 业务目的：共享成员即使绕过前端直调管理接口也必须返回 403，且管理写操作没有任何副作用。
     */
    @Test
    void memberCannotBypassAdminAuthorization() throws Exception {
        Cookie memberCookie = loginCookie("member");

        mockMvc.perform(post("/api/admin/probe").cookie(memberCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        assertThat(probeController.writeCount()).isZero();
    }

    /**
     * 业务目的：服务端目录解析出的管理员角色可以通过相同路由授权链执行写操作。
     */
    @Test
    void configuredAdminCanAccessAdminApi() throws Exception {
        Cookie adminCookie = loginCookie("admin");

        mockMvc.perform(post("/api/admin/probe").cookie(adminCookie))
                .andExpect(status().isOk());

        assertThat(probeController.writeCount()).isEqualTo(1);
    }

    /**
     * 业务目的：即使请求携带看似有效的 MCP Bearer Token，没有 Web 会话时也不能调用管理接口。
     */
    @Test
    void mcpBearerTokenCannotActAsWebAdministrator() throws Exception {
        mockMvc.perform(post("/api/admin/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer machine-only-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));

        assertThat(probeController.writeCount()).isZero();
    }

    /**
     * 业务目的：MVP 会话只保存在单实例内存中，应用重启后的旧 Cookie 必须失效而不是恢复成未知身份。
     */
    @Test
    void replacingInMemorySessionStoreInvalidatesExistingCookie() throws Exception {
        Cookie sessionCookie = loginCookie("admin");

        SaTokenDaoDefaultImpl restartedStore = new SaTokenDaoDefaultImpl();
        restartedStore.init();
        SaManager.setSaTokenDao(restartedStore);

        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
    }

    /**
     * 业务目的：错误凭据只返回稳定安全错误，不得把提交密码、哈希或会话令牌写入响应。
     */
    @Test
    void invalidCredentialsReturnSafeErrorBody() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"submitted-secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = login(username, "correct-password")
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("loredock_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIdentityConfiguration {

        @Bean
        AccountService accountService() {
            return TestAuthFactory.accountService();
        }

    }

    @RestController
    static class ProtectedProbeController {
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();

        @GetMapping("/api/projects/probe")
        Map<String, String> read() {
            reads.incrementAndGet();
            return Map.of("status", "read");
        }

        @PostMapping("/api/admin/probe")
        void write() {
            writes.incrementAndGet();
        }

        int readCount() {
            return reads.get();
        }

        int writeCount() {
            return writes.get();
        }

        void reset() {
            reads.set(0);
            writes.set(0);
        }
    }
}
