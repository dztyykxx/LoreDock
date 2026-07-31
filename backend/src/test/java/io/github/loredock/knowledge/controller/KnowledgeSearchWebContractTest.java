package io.github.loredock.knowledge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import io.github.loredock.auth.controller.AuthController;
import io.github.loredock.auth.service.AccountService;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.knowledge.converter.KnowledgeSearchHttpContract;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.exception.KnowledgeIndexUnavailableException;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.request.KnowledgeSearchQuery;
import io.github.loredock.knowledge.model.response.KnowledgeSearchResponse;
import io.github.loredock.knowledge.model.result.KnowledgeSearchContext;
import io.github.loredock.knowledge.service.search.KnowledgeSearchService;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.exception.ProjectNotFoundException;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
        controllers = {AuthController.class, KnowledgeSearchController.class},
        properties = {
                "sa-token.token-name=loredock_session",
                "sa-token.is-read-header=false",
                "sa-token.is-read-body=false",
                "sa-token.is-read-cookie=true",
                "sa-token.is-lasting-cookie=true",
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

        KnowledgeSearchWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class KnowledgeSearchWebContractTest {

    private static final Long GENERATION_ID = 2891640495451214098L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeSearchService searches;

    @BeforeEach
    void resetSessionsAndSearches() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(searches);
    }

    /**
     * 业务目的：知识搜索必须先验证 Web 会话，匿名请求不能通过查询参数枚举项目、分支或知识存在性。
     */
    @Test
    void knowledgeSearchRequiresAuthenticatedAdminOrMember() throws Exception {
        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", "发布流程").queryParam("context", "GLOBAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
        verify(searches, never()).search(any());
    }

    /**
     * 业务目的：GLOBAL 与 PROJECT 必须共享三种模式、默认 main 和全部过滤，并返回固定 generation 的有限可引用结构。
     */
    @Test
    void memberCanSearchGlobalAndProjectKnowledgeWithAllPublicParameters() throws Exception {
        when(searches.search(any())).thenReturn(response());
        Cookie member = loginCookie("member");

        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", "怎么发布场景包")
                        .queryParam("context", "PROJECT")
                        .queryParam("project", "network-tool")
                        .queryParam("mode", "HYBRID")
                        .queryParam("tag", "发布", "场景包")
                        .queryParam("format", "MARKDOWN")
                        .queryParam("sourceType", "WIKI")
                        .queryParam("limit", "5")
                        .cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.type").value("PROJECT"))
                .andExpect(jsonPath("$.context.branch").value("main"))
                .andExpect(jsonPath("$.mode").value("HYBRID"))
                .andExpect(jsonPath("$.generationId").value(GENERATION_ID.toString()))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(jsonPath("$.embedding").doesNotExist());

        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", "术语").queryParam("context", "GLOBAL")
                        .queryParam("mode", "KEYWORD").cookie(member))
                .andExpect(status().isOk());
        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", "相似说法").queryParam("context", "GLOBAL")
                        .queryParam("mode", "SEMANTIC").cookie(member))
                .andExpect(status().isOk());
        verify(searches, org.mockito.Mockito.times(3)).search(any(KnowledgeSearchQuery.class));
    }

    /**
     * 业务目的：字段非法、未知项目或分支以及两类检索基础设施失败必须保持 400/404/503 的可区分错误语义。
     */
    @Test
    void validationScopeAndInfrastructureFailuresUseStableSafeErrors() throws Exception {
        Cookie member = loginCookie("member");
        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", " ").queryParam("context", "GLOBAL").cookie(member))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        doThrow(new ProjectNotFoundException()).when(searches).search(any());
        search(member).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        doThrow(new BranchNotFoundException()).when(searches).search(any());
        search(member).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
        doThrow(new KnowledgeIndexUnavailableException()).when(searches).search(any());
        search(member).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INDEX_UNAVAILABLE"));
        doThrow(new KnowledgeEmbeddingUnavailableException()).when(searches).search(any());
        search(member).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_UNAVAILABLE"));
    }

    /**
     * 业务目的：客户端附加 generation、向量、候选数、SQL 或融合权重时不能控制内部检索，也不能让这些字段进入响应。
     */
    @Test
    void internalSearchParametersCannotControlRetrieval() throws Exception {
        when(searches.search(any())).thenReturn(response());
        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", "发布").queryParam("context", "GLOBAL")
                        .queryParam("generationId", "attacker-generation")
                        .queryParam("vector", "[1,2]")
                        .queryParam("candidateLimit", "99999")
                        .queryParam("sql", "select secret")
                        .queryParam("fusionWeight", "99")
                        .cookie(loginCookie("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generationId").value(GENERATION_ID.toString()))
                .andExpect(jsonPath("$.vector").doesNotExist())
                .andExpect(jsonPath("$.candidateLimit").doesNotExist());
        verify(searches).search(any(KnowledgeSearchQuery.class));
    }

    private org.springframework.test.web.servlet.ResultActions search(Cookie cookie) throws Exception {
        return mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                .queryParam("query", "发布").queryParam("context", "PROJECT")
                .queryParam("project", "network-tool").cookie(cookie));
    }

    private KnowledgeSearchResponse response() {
        return new KnowledgeSearchResponse(
                new KnowledgeSearchContext(KnowledgeBrowseContextType.PROJECT, "network-tool", "main"),
                KnowledgeSearchMode.HYBRID,
                GENERATION_ID,
                List.of(),
                List.of());
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("loredock_session");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIdentityConfiguration {

        @Bean
        AccountService accountService() {
            return TestAuthFactory.accountService();
        }

    }
}
