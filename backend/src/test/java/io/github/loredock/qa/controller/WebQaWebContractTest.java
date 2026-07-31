package io.github.loredock.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import io.github.loredock.agent.api.AgentRequestException;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.auth.TestAuthFactory;
import io.github.loredock.auth.config.IdentityRoleConfiguration;
import io.github.loredock.auth.config.IdentityWebConfiguration;
import io.github.loredock.auth.controller.AuthController;
import io.github.loredock.auth.service.AccountService;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.exception.ProjectNotFoundException;
import io.github.loredock.qa.exception.WebQaQuestionNotFoundException;
import io.github.loredock.qa.model.command.CreateWebQaQuestionCommand;
import io.github.loredock.qa.model.command.QueryWebQaDetailCommand;
import io.github.loredock.qa.model.command.QueryWebQaHistoryCommand;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.request.WebQaSseStreamRequest;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionPage;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.result.WebQaStreamTarget;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import io.github.loredock.qa.service.CreateWebQaQuestionService;
import io.github.loredock.qa.service.QueryWebQaQuestionService;
import io.github.loredock.qa.service.WebQaSseService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(
        controllers = {AuthController.class, WebQaController.class, WebQaSseController.class},
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

        WebQaWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class WebQaWebContractTest {
    private static final Long QUESTION_ID = 3745426052800315394L;
    private static final Long RUN_ID = 3745426052800315395L;
    private static final Long PROJECT_ID = 3745426052800315396L;
    private static final Long BRANCH_ID = 3745426052800315397L;
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CreateWebQaQuestionService creates;
    @MockitoBean private QueryWebQaQuestionService queries;
    @MockitoBean private AgentService agents;
    @MockitoBean private WebQaSseService streams;

    @BeforeEach
    void resetSessionsAndUseCases() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(creates, queries, agents, streams);
    }

    /**
     * 业务目的：ADMIN 与 MEMBER 都能创建问答，服务端身份和角色必须覆盖任何客户端猜测并返回 202 固定范围。
     */
    @Test
    void authenticatedRolesCreateAcceptedQuestionWithServerIdentity() throws Exception {
        when(creates.create(any())).thenReturn(snapshot());
        when(agents.lastEventSequence(RUN_ID, "member")).thenReturn(1L);
        when(agents.lastEventSequence(RUN_ID, "admin")).thenReturn(1L);

        mockMvc.perform(post("/api/projects/atlas/qa/questions")
                        .cookie(loginCookie("member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"client-key\",\"branch\":\"main\","
                                + "\"question\":\"为什么必须校验引用？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.questionId").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.scope.projectIdentifier").value("atlas"))
                .andExpect(jsonPath("$.scope.branch").value("main"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.trustState").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.lastEventSequence").value(1));

        verify(creates).create(CreateWebQaQuestionCommand.of(
                "member", "MEMBER", "client-key", "atlas", "main", "为什么必须校验引用？"));

        mockMvc.perform(post("/api/projects/atlas/qa/questions")
                        .cookie(loginCookie("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"admin-key\",\"question\":\"管理员问题\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.questionId").value(QUESTION_ID.toString()));
        verify(creates).create(CreateWebQaQuestionCommand.of(
                "admin", "ADMIN", "admin-key", "atlas", null, "管理员问题"));
        System.out.printf("测试证据：场景=双角色创建问答，questionId=%s，runId=%s，角色=MEMBER+ADMIN，HTTP=202%n",
                QUESTION_ID, RUN_ID);
    }

    /**
     * 业务目的：匿名和无效输入必须在业务写入前返回 401/400，防止创建无归属或超长问题。
     */
    @Test
    void anonymousAndInvalidRequestsDoNotReachCreateUseCase() throws Exception {
        mockMvc.perform(post("/api/projects/atlas/qa/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"client-key\",\"question\":\"问题\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));

        mockMvc.perform(post("/api/projects/atlas/qa/questions")
                        .cookie(loginCookie("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"\",\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(creates, never()).create(any());
        System.out.println("测试证据：场景=问答入口认证与校验，匿名=401，空字段=400，创建调用=0");
    }

    /**
     * 业务目的：同键冲突和详情防枚举必须分别保持 409 与统一 404，不能泄露原记录归属。
     */
    @Test
    void conflictAndHiddenDetailUseStableErrors() throws Exception {
        Cookie member = loginCookie("member");
        when(creates.create(any())).thenThrow(new AgentRequestException(
                AgentRun.ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT));
        mockMvc.perform(post("/api/projects/atlas/qa/questions")
                        .cookie(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"client-key\",\"question\":\"不同问题\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_IDEMPOTENCY_CONFLICT"));

        when(queries.detail(any())).thenThrow(new WebQaQuestionNotFoundException());
        mockMvc.perform(get("/api/projects/other/qa/questions/{questionId}", QUESTION_ID).cookie(member))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QA_QUESTION_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("atlas"))));

        verify(queries).detail(new QueryWebQaDetailCommand("member", "other", QUESTION_ID));
        System.out.println("测试证据：场景=问答稳定错误，同键异参=409，跨项目猜测=404");
    }

    /**
     * 业务目的：创建阶段的项目与分支不存在必须保留各自 404 语义，不能被问答防枚举错误或通用 500 覆盖。
     */
    @Test
    void invalidCreationScopePreservesProjectAndBranchNotFound() throws Exception {
        Cookie member = loginCookie("member");
        when(creates.create(any())).thenThrow(new ProjectNotFoundException());
        mockMvc.perform(post("/api/projects/missing/qa/questions")
                        .cookie(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"project-key\",\"question\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        reset(creates);
        when(creates.create(any())).thenThrow(new BranchNotFoundException());
        mockMvc.perform(post("/api/projects/atlas/qa/questions")
                        .cookie(member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"branch-key\",\"branch\":\"missing\","
                                + "\"question\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
        System.out.println("测试证据：场景=创建范围错误，缺失项目=PROJECT_NOT_FOUND，缺失分支=BRANCH_NOT_FOUND");
    }

    /**
     * 业务目的：历史和详情只能公开运行时安全快照，绝不能序列化操作者、请求摘要、generation、对象键或服务器路径。
     */
    @Test
    void historyAndDetailExcludeInternalFields() throws Exception {
        WebQaQuestionSnapshot snapshot = snapshot();
        when(queries.history(any())).thenReturn(new WebQaQuestionPage(List.of(snapshot), "next-safe-cursor"));
        when(queries.detail(any())).thenReturn(snapshot);
        when(agents.lastEventSequence(RUN_ID, "member")).thenReturn(7L);
        Cookie member = loginCookie("member");

        mockMvc.perform(get("/api/projects/atlas/qa/questions")
                        .queryParam("cursor", "opaque-cursor").queryParam("limit", "25").cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].messages[0].content").value("为什么必须校验引用？"))
                .andExpect(jsonPath("$.items[0].citations[0].repositoryPath").value("src/Auth.java"))
                .andExpect(jsonPath("$.nextCursor").value("next-safe-cursor"));

        MvcResult detail = mockMvc.perform(
                        get("/api/projects/atlas/qa/questions/{questionId}", QUESTION_ID).cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastEventSequence").value(7))
                .andReturn();
        String json = detail.getResponse().getContentAsString();
        assertThat(json).doesNotContain(
                "operatorId", "idempotencyKey", "requestHash", "knowledgeGenerationId",
                "versions", "objectKey", "/srv/loredock");
        verify(queries).history(new QueryWebQaHistoryCommand("member", "atlas", "opaque-cursor", 25));
        verify(queries).detail(new QueryWebQaDetailCommand("member", "atlas", QUESTION_ID));
        System.out.printf("测试证据：场景=问答安全响应，questionId=%s，事件末序号=7，内部字段泄露=false%n",
                QUESTION_ID);
    }

    /**
     * 业务目的：SSE 必须先按 URL 问答授权，再把标准续读序号固定到连接请求，不能接受任意 runId。
     */
    @Test
    void sseEndpointAuthorizesQuestionAndUsesLastEventId() throws Exception {
        WebQaQuestionSnapshot snapshot = snapshot();
        when(queries.authorize(any())).thenReturn(
                new WebQaStreamTarget(snapshot.question(), snapshot.run()));
        when(streams.open(any())).thenReturn(new SseEmitter(1_000L));

        mockMvc.perform(get("/api/projects/atlas/qa/questions/{questionId}/events", QUESTION_ID)
                        .header("Last-Event-ID", "8")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .cookie(loginCookie("member")))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(queries).authorize(new QueryWebQaDetailCommand("member", "atlas", QUESTION_ID));
        var request = org.mockito.ArgumentCaptor.forClass(WebQaSseStreamRequest.class);
        verify(streams).open(request.capture());
        assertThat(request.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(request.getValue().afterSequence()).isEqualTo(8);
        assertThat(request.getValue().sessionLease()).isNotNull();
        System.out.printf("测试证据：场景=SSE建连，questionId=%s，runId=%s，afterSequence=8，异步=true%n",
                QUESTION_ID, RUN_ID);
    }

    /**
     * 业务目的：SSE 两种续读输入不一致时必须在查询问答和创建后台任务前返回 400，避免事件缺失或重复。
     */
    @Test
    void conflictingSseCursorReturnsBadRequestBeforeStreamCreation() throws Exception {
        mockMvc.perform(get("/api/projects/atlas/qa/questions/{questionId}/events", QUESTION_ID)
                        .header("Last-Event-ID", "8")
                        .queryParam("afterSequence", "7")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .cookie(loginCookie("member")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(queries, never()).authorize(any());
        verify(streams, never()).open(any());
        System.out.println("测试证据：场景=SSE游标冲突，Last-Event-ID=8，afterSequence=7，HTTP=400，后台任务=0");
    }

    /**
     * 业务目的：SSE 建连前仍必须执行统一认证和问答防枚举，EventSource 的 Accept 头不能把 401/404 退化为 406。
     */
    @Test
    void ssePreflightReturnsJsonAuthenticationAndNotFoundErrors() throws Exception {
        mockMvc.perform(get("/api/projects/atlas/qa/questions/{questionId}/events", QUESTION_ID)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));

        when(queries.authorize(any())).thenThrow(new WebQaQuestionNotFoundException());
        mockMvc.perform(get("/api/projects/atlas/qa/questions/{questionId}/events", QUESTION_ID)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .cookie(loginCookie("member")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("QA_QUESTION_NOT_FOUND"));

        verify(streams, never()).open(any());
        System.out.println("测试证据：场景=SSE建连前错误，匿名=401，隐藏问答=404，Content-Type=application/json");
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("loredock_session");
    }

    private WebQaQuestionSnapshot snapshot() {
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "client-key", "a".repeat(64), PROJECT_ID, "atlas",
                BRANCH_ID, "main", RUN_ID, NOW);
        AgentRun.Citation citation = new AgentRun.Citation(
                8000000000000000158L, AgentRun.EvidenceSourceType.CODE, null, 8000000000000000159L, "atlas", "main",
                "abcdef1", "src/Auth.java", "Auth.java", NOW, 1,
                new AgentRun.SourceMetadata(null, null, null, null, null));
        AgentRun run = new AgentRun(
                RUN_ID, AgentRun.Status.ACCEPTED, null, null, null, null, null,
                new AgentRun.Scope(PROJECT_ID, "atlas", BRANCH_ID, "main",
                        8000000000000000160L, "abcdef1", 8000000000000000161L),
                0, 0, NOW, null, null, List.of(citation));
        WebQaMessageRecord message = new WebQaMessageRecord(
                8000000000000000162L, QUESTION_ID, WebQaMessageRole.USER, "为什么必须校验引用？",
                null, null, NOW);
        return new WebQaQuestionSnapshot(question, run, WebQaTrustState.IN_PROGRESS, List.of(message));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIdentityConfiguration {
        @Bean
        AccountService accountService() {
            return TestAuthFactory.accountService();
        }

    }
}
