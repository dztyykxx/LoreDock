package io.github.loredock.knowledgegap.infrastructure.web;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
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
import io.github.loredock.knowledgegap.application.AdminKnowledgeGapUseCase;
import io.github.loredock.knowledgegap.application.CreateKnowledgeGapCommand;
import io.github.loredock.knowledgegap.application.CreateKnowledgeGapUseCase;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackPage;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackRecord;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackSnapshot;
import io.github.loredock.knowledgegap.application.KnowledgeGapIdempotencyConflictException;
import io.github.loredock.knowledgegap.application.KnowledgeGapNotFoundException;
import io.github.loredock.knowledgegap.application.KnowledgeGapStatusConflictException;
import io.github.loredock.knowledgegap.application.QueryKnowledgeGapsCommand;
import io.github.loredock.knowledgegap.application.UpdateKnowledgeGapStatusCommand;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {AuthController.class, KnowledgeGapController.class, AdminKnowledgeGapController.class},
        properties = {
                "sa-token.token-name=loredock_session",
                "sa-token.is-read-header=false",
                "sa-token.is-read-body=false",
                "sa-token.is-read-cookie=true",
                "sa-token.cookie.path=/",
                "sa-token.cookie.secure=true",
                "sa-token.cookie.http-only=true",
                "sa-token.cookie.same-site=Strict"
        })
@Import({
        IdentityWebConfiguration.class, IdentityRoleConfiguration.class, SaTokenWebSessionAdapter.class,
        WebSessionService.class, KnowledgeGapWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class, SaBeanInject.class, SaTokenContextRegister.class,
        GlobalExceptionHandler.class, PlatformConfiguration.class, SensitiveDataRedactor.class
})
class KnowledgeGapWebContractTest {
    private static final UUID FEEDBACK_ID = UUID.fromString("78000000-0000-0000-0000-000000000001");
    private static final UUID QUESTION_ID = UUID.fromString("78000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("78000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-07-30T13:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CreateKnowledgeGapUseCase creates;
    @MockitoBean private AdminKnowledgeGapUseCase manages;

    @BeforeEach
    void resetSessionAndUseCases() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(creates, manages);
    }

    /**
     * 业务目的：成员和管理员都可创建反馈，服务端会话身份必须进入命令且响应不泄露请求摘要。
     */
    @Test
    void authenticatedRolesCreateFeedbackWithServerIdentity() throws Exception {
        when(creates.create(any())).thenReturn(snapshot(KnowledgeGapStatus.OPEN));

        for (String username : List.of("member", "admin")) {
            mockMvc.perform(post("/api/projects/atlas/knowledge-gaps")
                            .cookie(loginCookie(username)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"idempotencyKey\":\"gap-key\",\"branch\":\"main\","
                                    + "\"type\":\"NO_ANSWER\",\"questionId\":\"" + QUESTION_ID + "\","
                                    + "\"question\":\"客户端伪造\",\"note\":\"需要补充\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.feedbackId").value(FEEDBACK_ID.toString()))
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.requestHash").doesNotExist())
                    .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
        }
        ArgumentCaptor<CreateKnowledgeGapCommand> command = ArgumentCaptor.forClass(CreateKnowledgeGapCommand.class);
        verify(creates, org.mockito.Mockito.times(2)).create(command.capture());
        assertThat(command.getAllValues()).extracting(CreateKnowledgeGapCommand::operatorId)
                .containsExactly("member", "admin");
        System.out.println("测试证据：场景=双角色创建知识缺口，HTTP=201，服务端身份=member/admin，摘要泄露=false");
    }

    /**
     * 业务目的：匿名和无关联问题缺失必须在进入业务用例前分别返回 401 与 400。
     */
    @Test
    void authenticationAndInputValidationPrecedeCreation() throws Exception {
        String body = "{\"idempotencyKey\":\"gap-key\",\"type\":\"NO_ANSWER\"}";
        mockMvc.perform(post("/api/projects/atlas/knowledge-gaps")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
        mockMvc.perform(post("/api/projects/atlas/knowledge-gaps")
                        .cookie(loginCookie("member")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(creates, never()).create(any());
        System.out.println("测试证据：场景=反馈入口认证校验，匿名=401，手动问题缺失=400，业务调用=0");
    }

    /**
     * 业务目的：成员不能绕过页面访问管理接口，管理员过滤参数必须原样进入有界查询。
     */
    @Test
    void memberIsForbiddenAndAdminCanFilterList() throws Exception {
        when(manages.list(any())).thenReturn(new KnowledgeGapFeedbackPage(
                List.of(snapshot(KnowledgeGapStatus.OPEN)), "next"));

        mockMvc.perform(get("/api/admin/knowledge-gaps").cookie(loginCookie("member")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(get("/api/admin/knowledge-gaps")
                        .cookie(loginCookie("admin"))
                        .param("project", "atlas").param("branch", "main")
                        .param("type", "NO_ANSWER").param("status", "OPEN").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].feedbackId").value(FEEDBACK_ID.toString()))
                .andExpect(jsonPath("$.nextCursor").value("next"));

        ArgumentCaptor<QueryKnowledgeGapsCommand> command = ArgumentCaptor.forClass(QueryKnowledgeGapsCommand.class);
        verify(manages).list(command.capture());
        assertThat(command.getValue().filter().projectIdentifier()).isEqualTo("atlas");
        assertThat(command.getValue().limit()).isEqualTo(10);
        System.out.println("测试证据：场景=反馈管理授权过滤，MEMBER=403，ADMIN项目=atlas，返回=1");
    }

    /**
     * 业务目的：状态更新只信任认证管理员账号，并保留 404 与两类 409 稳定错误语义。
     */
    @Test
    void adminStatusUsesAuthenticatedActorAndStableErrors() throws Exception {
        when(manages.updateStatus(any())).thenReturn(snapshot(KnowledgeGapStatus.ACKNOWLEDGED));
        mockMvc.perform(patch("/api/admin/knowledge-gaps/{id}/status", FEEDBACK_ID)
                        .cookie(loginCookie("admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        ArgumentCaptor<UpdateKnowledgeGapStatusCommand> update =
                ArgumentCaptor.forClass(UpdateKnowledgeGapStatusCommand.class);
        verify(manages).updateStatus(update.capture());
        assertThat(update.getValue().actor()).isEqualTo("admin");

        when(manages.detail(FEEDBACK_ID)).thenThrow(new KnowledgeGapNotFoundException());
        mockMvc.perform(get("/api/admin/knowledge-gaps/{id}", FEEDBACK_ID).cookie(loginCookie("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GAP_NOT_FOUND"));
        when(creates.create(any())).thenThrow(new KnowledgeGapIdempotencyConflictException());
        mockMvc.perform(post("/api/projects/atlas/knowledge-gaps")
                        .cookie(loginCookie("member")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"gap-key\",\"type\":\"NO_ANSWER\","
                                + "\"question\":\"为什么？\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GAP_IDEMPOTENCY_CONFLICT"));
        when(manages.updateStatus(any())).thenThrow(new KnowledgeGapStatusConflictException());
        mockMvc.perform(patch("/api/admin/knowledge-gaps/{id}/status", FEEDBACK_ID)
                        .cookie(loginCookie("admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GAP_STATUS_CONFLICT"));
        System.out.println("测试证据：场景=反馈稳定错误，缺失=404，幂等/状态冲突=409，更新操作者=admin");
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie("loredock_session");
    }

    private KnowledgeGapFeedbackSnapshot snapshot(KnowledgeGapStatus status) {
        return new KnowledgeGapFeedbackSnapshot(new KnowledgeGapFeedbackRecord(
                FEEDBACK_ID, "member", "gap-key", "a".repeat(64), UUID.randomUUID(), "atlas",
                UUID.randomUUID(), "main", QUESTION_ID, RUN_ID, KnowledgeGapType.NO_ANSWER, status,
                "服务端真实问题", "需要补充", AgentResultType.REFUSAL,
                AgentRefusalReason.INSUFFICIENT_EVIDENCE, null, NOW, NOW, "member",
                status == KnowledgeGapStatus.OPEN ? "member" : "admin"), List.of(UUID.randomUUID()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIdentityConfiguration {
        @Bean
        FixedAccountDirectory fixedAccountDirectory() {
            List<FixedAccount> accounts = List.of(
                    new FixedAccount("admin", "管理员", WebRole.ADMIN, "not-exposed-admin-hash"),
                    new FixedAccount("member", "组内成员", WebRole.MEMBER, "not-exposed-member-hash"));
            return new FixedAccountDirectory() {
                @Override public Optional<FixedAccount> findByUsername(String username) {
                    return accounts.stream().filter(account -> account.username().equals(username)).findFirst();
                }
                @Override public Collection<FixedAccount> configuredAccounts() { return accounts; }
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
