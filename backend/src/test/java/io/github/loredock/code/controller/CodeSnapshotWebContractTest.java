package io.github.loredock.code.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import io.github.loredock.code.converter.CodeSnapshotHttpContract;
import io.github.loredock.code.exception.CodeSnapshotJobNotFoundException;
import io.github.loredock.code.exception.CodeSnapshotTooLargeException;
import io.github.loredock.code.exception.CodeSnapshotTypeUnsupportedException;
import io.github.loredock.code.exception.CodeSnippetRangeInvalidException;
import io.github.loredock.code.exception.ProjectDisabledException;
import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.response.CodeSearchResponse;
import io.github.loredock.code.model.response.CodeSnippetResponse;
import io.github.loredock.code.model.result.ActiveCodeSnapshotView;
import io.github.loredock.code.model.result.CodeSearchResult;
import io.github.loredock.code.model.result.CodeSnapshotAdminPage;
import io.github.loredock.code.model.result.CodeSnapshotAdminView;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.service.AdminCodeSnapshotQueryService;
import io.github.loredock.code.service.CodeQueryServiceImpl;
import io.github.loredock.code.service.CodeSnapshotUploadService;
import io.github.loredock.job.api.JobService;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
        controllers = {AuthController.class, AdminCodeSnapshotController.class, CodeSnapshotController.class,
                CodeSearchController.class, CodeSnippetController.class},
        properties = {
                "sa-token.token-name=loredock_session",
                "sa-token.is-read-header=false",
                "sa-token.is-read-body=false",
                "sa-token.is-read-cookie=true",
                "sa-token.cookie.path=/",
                "sa-token.cookie.secure=true",
                "sa-token.cookie.http-only=true",
                "sa-token.cookie.same-site=Strict",
                "loredock.code.enabled=true"
        }
)
@Import({
        IdentityWebConfiguration.class,
        IdentityRoleConfiguration.class,
        SessionService.class,

        CodeSnapshotWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class CodeSnapshotWebContractTest {

    private static final Long PROJECT_ID = 2891640495451214098L;
    private static final Long BRANCH_ID = 5783280990902428195L;
    private static final Long SNAPSHOT_ID = 1674921486353642292L;
    private static final Long JOB_ID = 4566561981804856389L;
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodeSnapshotUploadService commands;

    @MockitoBean
    private AdminCodeSnapshotQueryService queries;

    @MockitoBean
    private CodeQueryServiceImpl codeQueries;

    @BeforeEach
    void resetSessionAndUseCases() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(commands, queries, codeQueries);
    }

    /**
     * 业务目的：上传是管理员写操作，未登录和成员请求都不能触发应用用例或保存代码正文。
     */
    @Test
    void uploadRequiresAdminBeforeInvokingApplicationUseCase() throws Exception {
        mockMvc.perform(uploadRequest(null)).andExpect(status().isUnauthorized());
        mockMvc.perform(uploadRequest(loginCookie("member")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        verify(commands, never()).upload(any());
    }

    /**
     * 业务目的：合法上传只返回 202 受理状态和可轮询元数据，不能泄漏对象键、正文或服务器路径。
     */
    @Test
    void adminUploadReturnsAcceptedSafeJobView() throws Exception {
        when(commands.upload(any())).thenReturn(jobView(JobService.Status.PENDING, null, null));

        mockMvc.perform(uploadRequest(loginCookie("admin")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.snapshotId").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.commit").value("abcdef1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.progress").value(0))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("class Secret"))));
        verify(commands).upload(any());
    }

    /**
     * 业务目的：同步资源上限失败必须保持稳定 413 错误，且响应不能回显上传正文或内部异常。
     */
    @Test
    void uploadResourceFailureUsesStableSafeError() throws Exception {
        when(commands.upload(any())).thenThrow(new CodeSnapshotTooLargeException());

        mockMvc.perform(uploadRequest(loginCookie("admin")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CODE_SNAPSHOT_TOO_LARGE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("class Secret"))));
    }

    /**
     * 业务目的：上传的格式、范围和并发冲突必须分别保持 400、404、409、415 稳定语义，不能被统一成 500。
     */
    @Test
    void uploadValidationAndScopeFailuresKeepStableHttpSemantics() throws Exception {
        Cookie admin = loginCookie("admin");

        when(commands.upload(any())).thenThrow(new IllegalArgumentException("bad commit"));
        mockMvc.perform(uploadRequest(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        reset(commands);

        when(commands.upload(any())).thenThrow(new io.github.loredock.project.exception.BranchNotFoundException());
        mockMvc.perform(uploadRequest(admin)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
        reset(commands);

        when(commands.upload(any())).thenThrow(new ProjectDisabledException());
        mockMvc.perform(uploadRequest(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_DISABLED"));
        reset(commands);

        when(commands.upload(any())).thenThrow(new ApplicationException(
                ErrorCode.CODE_SNAPSHOT_JOB_ACTIVE, "active code job"));
        mockMvc.perform(uploadRequest(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_SNAPSHOT_JOB_ACTIVE"));
        reset(commands);

        when(commands.upload(any())).thenThrow(new CodeSnapshotTypeUnsupportedException());
        mockMvc.perform(uploadRequest(admin)).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("CODE_SNAPSHOT_TYPE_UNSUPPORTED"));
    }

    /**
     * 业务目的：管理列表必须传递明确筛选和分页，并以稳定分页结构返回所有生命周期状态而不暴露物理位置。
     */
    @Test
    void adminListMapsStablePageWithoutStorageDetails() throws Exception {
        when(queries.list(any())).thenReturn(new CodeSnapshotAdminPage(
                List.of(adminView()), 1, 10, 21, 3));

        mockMvc.perform(get(CodeSnapshotHttpContract.ADMIN_SNAPSHOT_PATH)
                        .queryParam("projectId", PROJECT_ID.toString())
                        .queryParam("branchId", BRANCH_ID.toString())
                        .queryParam("page", "1").queryParam("size", "10")
                        .cookie(loginCookie("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].snapshotId").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].inputObjectKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].generationPath").doesNotExist());
        verify(queries).list(new io.github.loredock.code.model.request.AdminCodeSnapshotQuery(
                PROJECT_ID, BRANCH_ID, 1, 10));
    }

    /**
     * 业务目的：分页越界或只传分支的筛选必须在入口拒绝，避免不稳定全表扫描和含糊范围。
     */
    @Test
    void adminListRejectsInvalidPaginationAndBranchOnlyScope() throws Exception {
        Cookie admin = loginCookie("admin");
        mockMvc.perform(get(CodeSnapshotHttpContract.ADMIN_SNAPSHOT_PATH)
                        .queryParam("branchId", BRANCH_ID.toString()).cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(CodeSnapshotHttpContract.ADMIN_SNAPSHOT_PATH)
                        .queryParam("page", "-1").queryParam("size", "101").cookie(admin))
                .andExpect(status().isBadRequest());
        verify(queries, never()).list(any());
    }

    /**
     * 业务目的：任务轮询要返回进度、计数和脱敏失败摘要，不能返回对象键或内部异常。
     */
    @Test
    void adminCanPollCompleteSanitizedJobStatus() throws Exception {
        when(queries.getJob(JOB_ID)).thenReturn(jobView(
                JobService.Status.FAILED, "CODE_SNAPSHOT_ARCHIVE_INVALID", "归档结构不安全"));

        mockMvc.perform(get(CodeSnapshotHttpContract.ADMIN_JOB_PATH + "/{jobId}", JOB_ID)
                        .cookie(loginCookie("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.indexedFileCount").value(7))
                .andExpect(jsonPath("$.ignoredFileCount").value(2))
                .andExpect(jsonPath("$.failureCode").value("CODE_SNAPSHOT_ARCHIVE_INVALID"))
                .andExpect(jsonPath("$.failureSummary").value("归档结构不安全"))
                .andExpect(jsonPath("$.inputObjectKey").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("java.lang"))));
    }

    /**
     * 业务目的：未知或非代码任务 ID 必须统一返回代码任务 404，避免枚举平台其他后台任务。
     */
    @Test
    void nonCodeJobIdIsReportedAsCodeJobNotFound() throws Exception {
        when(queries.getJob(JOB_ID)).thenThrow(new CodeSnapshotJobNotFoundException());

        mockMvc.perform(get(CodeSnapshotHttpContract.ADMIN_JOB_PATH + "/{jobId}", JOB_ID)
                        .cookie(loginCookie("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODE_SNAPSHOT_JOB_NOT_FOUND"));
    }

    /**
     * 业务目的：管理员可以非幂等提交当前活动快照重建并获得 202，成员请求必须在调用应用层前被拒绝。
     */
    @Test
    void reindexReturnsAcceptedForAdminAndRejectsMember() throws Exception {
        when(commands.reindex(SNAPSHOT_ID)).thenReturn(jobView(JobService.Status.PENDING, null, null));
        mockMvc.perform(post(CodeSnapshotHttpContract.ADMIN_SNAPSHOT_PATH + "/{snapshotId}/reindex", SNAPSHOT_ID)
                        .cookie(loginCookie("admin")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.snapshotId").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        reset(commands);
        mockMvc.perform(post(CodeSnapshotHttpContract.ADMIN_SNAPSHOT_PATH + "/{snapshotId}/reindex", SNAPSHOT_ID)
                        .cookie(loginCookie("member")))
                .andExpect(status().isForbidden());
        verify(commands, never()).reindex(SNAPSHOT_ID);
    }

    /**
     * 业务目的：已登录成员可查询明确分支的活动摘要，响应不得包含 generation、对象键或服务器目录；未登录仍为 401。
     */
    @Test
    void memberCanReadSafeActiveSnapshotStatus() throws Exception {
        when(codeQueries.get("alpha", null)).thenReturn(new ActiveCodeSnapshotView(
                "alpha", "main", CodeSnapshotAvailability.INDEXED, SNAPSHOT_ID, "abcdef1", NOW,
                7L, CodeSnapshotChangeHint.INITIAL));

        mockMvc.perform(get("/api/projects/{identifier}/code-snapshot", "alpha"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/projects/{identifier}/code-snapshot", "alpha")
                        .cookie(loginCookie("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.commit").value("abcdef1"))
                .andExpect(jsonPath("$.changeHint").value("INITIAL"))
                .andExpect(jsonPath("$.generationId").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    /**
     * 业务目的：成员搜索请求必须完整映射字段、范围和上限，响应只包含有限纯文本及固定 commit 来源。
     */
    @Test
    void memberCanSearchActiveCodeWithSafeSourceMetadata() throws Exception {
        when(codeQueries.search(any(io.github.loredock.code.model.request.CodeSearchQuery.class)))
                .thenReturn(new CodeSearchResponse(List.of(new CodeSearchResult(
                "alpha", "main", SNAPSHOT_ID, "abcdef1", NOW, "src/A.java", "class A {}", 2.5f, false))));

        mockMvc.perform(get("/api/projects/{identifier}/code-search", "alpha")
                        .queryParam("query", "A:(*)").queryParam("target", "ALL")
                        .queryParam("pathPrefix", "src").queryParam("limit", "5")
                        .cookie(loginCookie("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commit").value("abcdef1"))
                .andExpect(jsonPath("$.items[0].path").value("src/A.java"))
                .andExpect(jsonPath("$.items[0].snippet").value("class A {}"))
                .andExpect(jsonPath("$.items[0].generationId").doesNotExist())
                .andExpect(jsonPath("$.items[0].objectKey").doesNotExist());
        verify(codeQueries).search(new io.github.loredock.code.model.request.CodeSearchQuery(
                "alpha", null, "A:(*)", io.github.loredock.code.model.enums.CodeSearchTarget.ALL, "src", 5));
    }

    /**
     * 业务目的：片段端点只返回固定活动 commit 的有限纯文本，越界使用 416，响应不允许指定或暴露历史/物理索引信息。
     */
    @Test
    void memberCanReadBoundedSnippetAndGetsStableRangeFailure() throws Exception {
        when(codeQueries.read(any(io.github.loredock.code.model.request.CodeSnippetQuery.class)))
                .thenReturn(new CodeSnippetResponse(
                "alpha", "main", SNAPSHOT_ID, "abcdef1", NOW, "src/A.java",
                2, 3, "two\nthree", true));
        Cookie member = loginCookie("member");

        mockMvc.perform(get("/api/projects/{identifier}/code-snippets", "alpha")
                        .queryParam("path", "src/A.java").queryParam("startLine", "2")
                        .queryParam("lineCount", "2").cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value("abcdef1"))
                .andExpect(jsonPath("$.content").value("two\nthree"))
                .andExpect(jsonPath("$.generationId").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());

        reset(codeQueries);
        when(codeQueries.read(any(io.github.loredock.code.model.request.CodeSnippetQuery.class)))
                .thenThrow(new CodeSnippetRangeInvalidException());
        mockMvc.perform(get("/api/projects/{identifier}/code-snippets", "alpha")
                        .queryParam("path", "src/A.java").queryParam("startLine", "999")
                        .cookie(member))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.code").value("CODE_SNIPPET_RANGE_INVALID"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder uploadRequest(
            Cookie cookie
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip", "class Secret {}".getBytes());
        var request = multipart(CodeSnapshotHttpContract.ADMIN_SNAPSHOT_PATH)
                .file(file)
                .param("projectId", PROJECT_ID.toString())
                .param("branchId", BRANCH_ID.toString())
                .param("commit", "abcdef1");
        return cookie == null ? request : request.cookie(cookie);
    }

    private CodeSnapshotJobView jobView(JobService.Status status, String failureCode, String failureSummary) {
        return new CodeSnapshotJobView(
                SNAPSHOT_ID, JOB_ID, PROJECT_ID, BRANCH_ID, "abcdef1", status,
                status == JobService.Status.PENDING ? 0 : 70,
                7, 2, NOW, status == JobService.Status.FAILED ? NOW.plusSeconds(5) : null,
                failureCode, failureSummary);
    }

    private CodeSnapshotAdminView adminView() {
        return new CodeSnapshotAdminView(
                SNAPSHOT_ID, PROJECT_ID, BRANCH_ID, "abcdef1", CodeSnapshotStatus.ACTIVE,
                7, 2, NOW, NOW.minusSeconds(5), NOW);
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
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
