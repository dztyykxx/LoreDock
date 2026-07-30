package io.github.loredock.qa.infrastructure.web;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import io.github.loredock.agent.application.AgentCitationSnapshot;
import io.github.loredock.agent.application.AgentEventQueryUseCase;
import io.github.loredock.agent.application.AgentRequestException;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.EvidenceSourceMetadata;
import io.github.loredock.agent.domain.EvidenceSourceType;
import io.github.loredock.identity.application.FixedAccount;
import io.github.loredock.identity.application.FixedAccountDirectory;
import io.github.loredock.identity.application.InvalidCredentialsException;
import io.github.loredock.identity.application.LoginUseCase;
import io.github.loredock.identity.domain.AuthenticatedActor;
import io.github.loredock.identity.domain.WebRole;
import io.github.loredock.identity.infrastructure.web.AuthController;
import io.github.loredock.identity.infrastructure.web.IdentityRoleConfiguration;
import io.github.loredock.identity.infrastructure.web.IdentityWebConfiguration;
import io.github.loredock.identity.infrastructure.web.SaTokenWebSessionAdapter;
import io.github.loredock.identity.infrastructure.web.WebSessionService;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import io.github.loredock.project.application.BranchNotFoundException;
import io.github.loredock.project.application.ProjectNotFoundException;
import io.github.loredock.qa.application.CreateWebQaQuestionCommand;
import io.github.loredock.qa.application.CreateWebQaQuestionUseCase;
import io.github.loredock.qa.application.QueryWebQaDetailCommand;
import io.github.loredock.qa.application.QueryWebQaHistoryCommand;
import io.github.loredock.qa.application.QueryWebQaQuestionUseCase;
import io.github.loredock.qa.application.WebQaMessageRecord;
import io.github.loredock.qa.application.WebQaQuestionNotFoundException;
import io.github.loredock.qa.application.WebQaQuestionPage;
import io.github.loredock.qa.application.WebQaQuestionRecord;
import io.github.loredock.qa.application.WebQaQuestionSnapshot;
import io.github.loredock.qa.domain.WebQaMessageRole;
import io.github.loredock.qa.domain.WebQaTrustState;
import jakarta.servlet.http.Cookie;
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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {AuthController.class, WebQaController.class},
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
        SaTokenWebSessionAdapter.class,
        WebSessionService.class,
        WebQaWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class WebQaWebContractTest {
    private static final UUID QUESTION_ID = UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("75000000-0000-0000-0000-000000000002");
    private static final UUID PROJECT_ID = UUID.fromString("75000000-0000-0000-0000-000000000003");
    private static final UUID BRANCH_ID = UUID.fromString("75000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CreateWebQaQuestionUseCase creates;
    @MockitoBean private QueryWebQaQuestionUseCase queries;
    @MockitoBean private AgentEventQueryUseCase events;

    @BeforeEach
    void resetSessionsAndUseCases() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(creates, queries, events);
    }

    /**
     * 业务目的：ADMIN 与 MEMBER 都能创建问答，服务端身份和角色必须覆盖任何客户端猜测并返回 202 固定范围。
     */
    @Test
    void authenticatedRolesCreateAcceptedQuestionWithServerIdentity() throws Exception {
        when(creates.create(any())).thenReturn(snapshot());
        when(events.lastSequence(RUN_ID, "member")).thenReturn(1L);
        when(events.lastSequence(RUN_ID, "admin")).thenReturn(1L);

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
                AgentErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT));
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
        when(events.lastSequence(RUN_ID, "member")).thenReturn(7L);
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
        AgentCitationSnapshot citation = new AgentCitationSnapshot(
                UUID.randomUUID(), EvidenceSourceType.CODE, null, UUID.randomUUID(), "atlas", "main",
                "abcdef1", "src/Auth.java", "Auth.java", NOW, 1,
                EvidenceSourceMetadata.historicalUnknown());
        AgentRunSnapshot run = new AgentRunSnapshot(
                RUN_ID, "member", "agent-key", "b".repeat(64), "project_qa", AgentRunStatus.ACCEPTED,
                null, null, null, null, null,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", UUID.randomUUID(), "abcdef1",
                        UUID.randomUUID(), List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "c".repeat(64),
                        "fake", "fake-model", "prompt", "tools", "limits"),
                10, 0, 0, null, null, NOW, null, null, List.of(citation));
        WebQaMessageRecord message = new WebQaMessageRecord(
                UUID.randomUUID(), QUESTION_ID, WebQaMessageRole.USER, "为什么必须校验引用？",
                null, null, NOW);
        return new WebQaQuestionSnapshot(question, run, WebQaTrustState.IN_PROGRESS, List.of(message));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIdentityConfiguration {
        @Bean
        FixedAccountDirectory fixedAccountDirectory() {
            List<FixedAccount> accounts = List.of(
                    new FixedAccount("admin", "管理员", WebRole.ADMIN, "not-exposed-admin-hash"),
                    new FixedAccount("member", "组内成员", WebRole.MEMBER, "not-exposed-member-hash"));
            return new FixedAccountDirectory() {
                @Override
                public Optional<FixedAccount> findByUsername(String username) {
                    return accounts.stream().filter(account -> account.username().equals(username)).findFirst();
                }

                @Override
                public Collection<FixedAccount> configuredAccounts() {
                    return accounts;
                }
            };
        }

        @Bean
        LoginUseCase loginUseCase(FixedAccountDirectory directory) {
            return command -> {
                if (!"correct-password".equals(command.password())) {
                    throw new InvalidCredentialsException();
                }
                FixedAccount account = directory.findByUsername(command.username())
                        .orElseThrow(InvalidCredentialsException::new);
                return new AuthenticatedActor(account.username(), account.displayName(), account.role());
            };
        }
    }
}
