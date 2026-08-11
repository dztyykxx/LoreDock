package io.github.loredock.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import io.github.loredock.job.api.JobService;
import io.github.loredock.knowledge.config.KnowledgeIndexJobTypes;
import io.github.loredock.knowledge.converter.KnowledgeDocumentImportHttpContract;
import io.github.loredock.knowledge.converter.KnowledgeIndexJobHttpContract;
import io.github.loredock.knowledge.exception.DocumentReplacementConflictException;
import io.github.loredock.knowledge.exception.DocumentStateConflictException;
import io.github.loredock.knowledge.exception.KnowledgeDocumentNotFoundException;
import io.github.loredock.knowledge.exception.KnowledgeImportArchiveInvalidException;
import io.github.loredock.knowledge.exception.KnowledgeImportBatchNotFoundException;
import io.github.loredock.knowledge.exception.KnowledgeImportTooLargeException;
import io.github.loredock.knowledge.exception.KnowledgeImportTypeUnsupportedException;
import io.github.loredock.knowledge.exception.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.model.DocumentRevision;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTag;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.ReplacementLink;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.ImportBatchStatus;
import io.github.loredock.knowledge.model.enums.ImportItemReason;
import io.github.loredock.knowledge.model.enums.ImportItemStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeIndexSyncStatus;
import io.github.loredock.knowledge.model.request.ReadKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.result.BatchPublishKnowledgeDocumentsResult;
import io.github.loredock.knowledge.model.result.KnowledgeBrowseResult;
import io.github.loredock.knowledge.model.result.KnowledgeDirectoryNode;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentSummary;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.model.result.KnowledgeImportBatchView;
import io.github.loredock.knowledge.model.result.KnowledgeImportItemView;
import io.github.loredock.knowledge.model.result.KnowledgeIndexJobView;
import io.github.loredock.knowledge.model.result.PageResult;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.knowledge.service.KnowledgeDocumentCommandService;
import io.github.loredock.knowledge.service.KnowledgeDocumentLifecycleService;
import io.github.loredock.knowledge.service.KnowledgeDocumentQueryService;
import io.github.loredock.knowledge.service.KnowledgeIndexJobService;
import io.github.loredock.knowledge.service.importing.KnowledgeDocumentImportService;
import io.github.loredock.knowledge.service.project.ProjectKnowledgeScopeResolver;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
        controllers = {AuthController.class, KnowledgeDocumentController.class, AdminKnowledgeDocumentController.class,
                KnowledgeDocumentImportController.class, KnowledgeIndexJobController.class},
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

        KnowledgeDocumentWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class KnowledgeDocumentWebContractTest {

    private static final Long DOCUMENT_ID = 2891640495451214098L;
    private static final Long PROJECT_ID = 5783280990902428195L;
    private static final Long BRANCH_ID = 1674921486353642292L;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeDocumentQueryService queries;

    @MockitoBean
    private ProjectKnowledgeScopeResolver scopeResolver;

    @MockitoBean
    private KnowledgeDocumentCommandService commands;

    @MockitoBean
    private KnowledgeDocumentLifecycleService lifecycle;

    @MockitoBean
    private KnowledgeDocumentImportService imports;

    @MockitoBean
    private KnowledgeIndexJobService indexJobs;

    @BeforeEach
    void resetSessionsAndUseCases() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(queries, scopeResolver, commands, lifecycle, imports, indexJobs);
    }

    /**
     * 业务目的：普通知识接口必须先验证 Web 会话，未登录用户不能借范围参数枚举目录或文档 ID。
     */
    @Test
    void ordinaryKnowledgeApiRequiresLoginBeforeResolvingScope() throws Exception {
        mockMvc.perform(get("/api/knowledge-documents").queryParam("context", "GLOBAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
        verify(scopeResolver, never()).resolveBrowse(any(), any(), any(), anyBoolean());
        verify(queries, never()).browse(any());
    }

    /**
     * 业务目的：GLOBAL 列表必须把明确上下文、根目录和默认分页交给应用层，并返回目录及无正文摘要。
     */
    @Test
    void memberCanBrowseGlobalKnowledgeWithStablePageContract() throws Exception {
        KnowledgeBrowseContext context = new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null);
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null, false)).thenReturn(context);
        when(queries.browse(any())).thenReturn(new KnowledgeBrowseResult(
                List.of(new KnowledgeDirectoryNode("guides", "guides", 1)),
                new PageResult<>(List.of(summary()), 0, 20, 1, 1)));

        mockMvc.perform(get("/api/knowledge-documents")
                        .queryParam("context", "GLOBAL").cookie(loginCookie("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directories[0].path").value("guides"))
                .andExpect(jsonPath("$.documents.items[0].id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.documents.items[0].title").value("Guide"))
                .andExpect(jsonPath("$.documents.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.documents.page").value(0))
                .andExpect(jsonPath("$.documents.size").value(20));
        verify(scopeResolver).resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null, false);
    }

    /**
     * 业务目的：PROJECT 请求的项目、默认或显式分支必须原样交给范围端口，Controller 不得自行回退 main。
     */
    @Test
    void projectBrowseDelegatesDefaultAndExplicitBranchResolution() throws Exception {
        KnowledgeBrowseContext context = new KnowledgeBrowseContext(
                KnowledgeBrowseContextType.PROJECT, PROJECT_ID, BRANCH_ID);
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null, false)).thenReturn(context);
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", "feature/a", false)).thenReturn(context);
        when(queries.browse(any())).thenReturn(new KnowledgeBrowseResult(
                List.of(), new PageResult<>(List.of(), 0, 10, 0, 0)));
        Cookie member = loginCookie("member");

        mockMvc.perform(get("/api/knowledge-documents")
                        .queryParam("context", "PROJECT").queryParam("project", "alpha")
                        .queryParam("size", "10").cookie(member))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/knowledge-documents")
                        .queryParam("context", "PROJECT").queryParam("project", "alpha")
                        .queryParam("branch", "feature/a").queryParam("size", "10").cookie(member))
                .andExpect(status().isOk());

        verify(scopeResolver).resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null, false);
        verify(scopeResolver).resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", "feature/a", false);
    }

    /**
     * 业务目的：详情响应返回完整纯文本正文和来源，但不得暴露管理审计、替代关系或内部对象键。
     */
    @Test
    void memberCanReadCompletePublishedDocumentWithoutAdminFields() throws Exception {
        KnowledgeBrowseContext context = new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null);
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null, false)).thenReturn(context);
        when(queries.get(any(ReadKnowledgeDocumentQuery.class))).thenReturn(view());

        mockMvc.perform(get("/api/knowledge-documents/{id}", DOCUMENT_ID)
                        .queryParam("context", "GLOBAL").cookie(loginCookie("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("<script>text only</script>"))
                .andExpect(jsonPath("$.source.wikiUrl").value("https://example.test/wiki"))
                .andExpect(jsonPath("$.publishedAt").value("2026-07-30T00:00:00Z"))
                .andExpect(jsonPath("$.publishedBy").doesNotExist())
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.replacement").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());
    }

    /**
     * 业务目的：非法范围是 400，跨项目、跨分支或非发布详情统一是 404，响应不得回显正文或内部异常。
     */
    @Test
    void invalidScopeAndInvisibleDocumentUseStableSafeErrors() throws Exception {
        Cookie member = loginCookie("member");
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.GLOBAL, "residual", null, false))
                .thenThrow(new KnowledgeScopeInvalidException());
        mockMvc.perform(get("/api/knowledge-documents")
                        .queryParam("context", "GLOBAL").queryParam("project", "residual").cookie(member))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_SCOPE_INVALID"));

        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null, false))
                .thenReturn(new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null));
        when(queries.get(any(ReadKnowledgeDocumentQuery.class))).thenThrow(new KnowledgeDocumentNotFoundException());
        mockMvc.perform(get("/api/knowledge-documents/{id}", DOCUMENT_ID)
                        .queryParam("context", "GLOBAL").cookie(member))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    /**
     * 业务目的：项目文档/草稿列表排除通用知识时，excludeGlobal 开关必须原样传给范围解析器，
     * 使目录计数、分页与详情读在同一隔离范围内执行。
     */
    @Test
    void projectBrowseWithExcludeGlobalForwardsFlagToScopeResolver() throws Exception {
        KnowledgeBrowseContext context = new KnowledgeBrowseContext(
                KnowledgeBrowseContextType.PROJECT, PROJECT_ID, BRANCH_ID, true);
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null, true))
                .thenReturn(context);
        when(queries.browse(any())).thenReturn(new KnowledgeBrowseResult(
                List.of(), new PageResult<>(List.of(), 0, 20, 0, 0)));

        mockMvc.perform(get("/api/knowledge-documents")
                        .queryParam("context", "PROJECT").queryParam("project", "alpha")
                        .queryParam("excludeGlobal", "true").cookie(loginCookie("member")))
                .andExpect(status().isOk());

        verify(scopeResolver).resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null, true);
        System.out.println("测试证据：场景=项目列表排除通用，excludeGlobal=true，范围开关已传递");
    }

    /**
     * 业务目的：ALL 全库范围只允许 Agent 内部路径构造；公开浏览端点传入必须 400（安全边界），
     * 防止普通成员借范围参数跨项目枚举文档。
     */
    @Test
    void publicBrowseRejectsAllScopeAsInvalid() throws Exception {
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.ALL, null, null, false))
                .thenThrow(new KnowledgeScopeInvalidException());

        mockMvc.perform(get("/api/knowledge-documents")
                        .queryParam("context", "ALL").cookie(loginCookie("member")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_SCOPE_INVALID"));
        System.out.println("测试证据：场景=公开浏览拒绝全库范围，context=ALL，稳定错误=DOCUMENT_SCOPE_INVALID");
    }

    /**
     * 业务目的：成员即使绕过前端直接请求管理写端点也必须在 Controller 前被拒绝，不能触发范围解析或保存副作用。
     */
    @Test
    void memberCannotWriteKnowledgeThroughAdminApi() throws Exception {
        mockMvc.perform(post("/api/admin/knowledge-documents")
                        .cookie(loginCookie("member")).contentType(MediaType.APPLICATION_JSON)
                        .content(validWriteJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        verify(scopeResolver, never()).resolveAdmin(any(), any(), any());
        verify(commands, never()).create(any());
    }

    /**
     * 业务目的：管理员列表与详情必须映射筛选、稳定分页、完整生命周期审计、替代关系和同步状态。
     */
    @Test
    void adminCanListAndReadCompleteKnowledgeMetadata() throws Exception {
        when(queries.list(any())).thenReturn(new PageResult<>(List.of(summary()), 0, 20, 1, 1));
        when(queries.get(DOCUMENT_ID)).thenReturn(view());
        Cookie admin = loginCookie("admin");

        mockMvc.perform(get("/api/admin/knowledge-documents")
                        .queryParam("scopeType", "GLOBAL").queryParam("status", "PUBLISHED")
                        .queryParam("directory", "guides").queryParam("tag", "tag").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.items[0].syncStatus").value("PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/admin/knowledge-documents/{id}", DOCUMENT_ID).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("<script>text only</script>"))
                .andExpect(jsonPath("$.publishedBy").value("admin"))
                .andExpect(jsonPath("$.replacement.replacesDocumentId").doesNotExist())
                .andExpect(jsonPath("$.syncStatus").value("PENDING"));
    }

    /**
     * 业务目的：管理员工作区必须通过单一上下文浏览契约获得完整目录和子树分页，不能继续在前端拼接有限列表。
     */
    @Test
    void adminCanBrowseSubtreeWithCombinedDirectoryContract() throws Exception {
        KnowledgeBrowseContext context = new KnowledgeBrowseContext(
                KnowledgeBrowseContextType.PROJECT, PROJECT_ID, BRANCH_ID);
        when(scopeResolver.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null, false)).thenReturn(context);
        when(queries.browseAdmin(any())).thenReturn(new KnowledgeBrowseResult(
                List.of(new KnowledgeDirectoryNode("测试资料", "测试资料", 18)),
                new PageResult<>(List.of(summary()), 0, 20, 18, 1)));

        mockMvc.perform(get("/api/admin/knowledge-documents/browse")
                        .queryParam("context", "PROJECT").queryParam("project", "alpha")
                        .queryParam("directory", "测试资料").queryParam("status", "DRAFT")
                        .cookie(loginCookie("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directories[0].documentCount").value(18))
                .andExpect(jsonPath("$.documents.totalElements").value(18));
        verify(scopeResolver).resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null, false);
        verify(queries).browseAdmin(argThat(query -> query.status() == DocumentStatus.DRAFT));
    }

    /**
     * 业务目的：批量发布响应必须给出实际数量，成员绕过前端时必须在调用生命周期服务前被拒绝。
     */
    @Test
    void adminCanBatchPublishWhileMemberIsForbidden() throws Exception {
        when(lifecycle.publishBatch(any())).thenReturn(new BatchPublishKnowledgeDocumentsResult(2, 2, 0));
        String request = "{\"documentIds\":[" + DOCUMENT_ID + "," + (DOCUMENT_ID + 1) + "]}";

        mockMvc.perform(post("/api/admin/knowledge-documents/batch-publish")
                        .cookie(loginCookie("admin")).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.publishedCount").value(2))
                .andExpect(jsonPath("$.alreadyPublishedCount").value(0));

        reset(lifecycle);
        mockMvc.perform(post("/api/admin/knowledge-documents/batch-publish")
                        .cookie(loginCookie("member")).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        verify(lifecycle, never()).publishBatch(any());
    }

    /**
     * 业务目的：管理员创建返回 201、同值 PUT 返回真实当前详情，全部字段必须先构造值对象和解析范围再调用应用用例。
     */
    @Test
    void adminCreateAndEditMapCompleteValidatedContracts() throws Exception {
        when(scopeResolver.resolveAdmin(any(), any(), any())).thenReturn(KnowledgeScope.global());
        when(commands.create(any())).thenReturn(view());
        when(commands.edit(any())).thenReturn(view());
        Cookie admin = loginCookie("admin");

        mockMvc.perform(post("/api/admin/knowledge-documents")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON).content(validWriteJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.format").value("MARKDOWN"))
                .andExpect(jsonPath("$.scope.type").value("GLOBAL"));
        mockMvc.perform(put("/api/admin/knowledge-documents/{id}", DOCUMENT_ID)
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON).content(validWriteJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        verify(commands).create(any());
        verify(commands).edit(any());
    }

    /**
     * 业务目的：无效管理字段必须返回 400 且不保存，未知详情与状态冲突分别保持 404/409 且错误体不回显正文。
     */
    @Test
    void adminValidationNotFoundAndConflictErrorsAreStableAndSafe() throws Exception {
        Cookie admin = loginCookie("admin");
        mockMvc.perform(post("/api/admin/knowledge-documents")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(validWriteJson().replace("\"Guide\"", "\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(commands, never()).create(any());

        when(queries.get(DOCUMENT_ID)).thenThrow(new KnowledgeDocumentNotFoundException());
        mockMvc.perform(get("/api/admin/knowledge-documents/{id}", DOCUMENT_ID).cookie(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    /**
     * 业务目的：发布、替代发布和归档端点必须返回提交后的真实状态，重复请求由用例幂等且替代目标原样传递。
     */
    @Test
    void adminLifecycleEndpointsReturnCommittedStateAndReplacementTrace() throws Exception {
        Long oldId = 4566561981804856389L;
        KnowledgeDocumentView published = new KnowledgeDocumentView(
                view().id(), view().format(), view().title(), view().body(), view().directory(), view().tags(),
                view().source(), view().scope(), view().status(), view().revision(), view().publishedAt(),
                view().publishedBy(), null, null, new ReplacementLink(oldId, null), view().syncStatus(),
                view().createdAt(), view().updatedAt(), view().createdBy(), view().updatedBy());
        KnowledgeDocumentView archived = new KnowledgeDocumentView(
                view().id(), view().format(), view().title(), view().body(), view().directory(), view().tags(),
                view().source(), view().scope(), DocumentStatus.ARCHIVED, new DocumentRevision(3),
                view().publishedAt(), view().publishedBy(), NOW.plusSeconds(10), "admin", view().replacement(),
                KnowledgeIndexSyncStatus.NOT_APPLICABLE, view().createdAt(), NOW.plusSeconds(10),
                view().createdBy(), "admin");
        when(lifecycle.publish(any())).thenReturn(published);
        when(lifecycle.archive(any())).thenReturn(archived);
        Cookie admin = loginCookie("admin");

        mockMvc.perform(post("/api/admin/knowledge-documents/{id}/publish", DOCUMENT_ID)
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replacesDocumentId\":\"" + oldId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.replacement.replacesDocumentId").value(oldId.toString()));
        mockMvc.perform(post("/api/admin/knowledge-documents/{id}/archive", DOCUMENT_ID).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.archivedBy").value("admin"));
    }

    /**
     * 业务目的：状态和替代冲突必须稳定映射为不同 409 错误码，失败操作不能被响应伪装成成功或泄露内部异常。
     */
    @Test
    void lifecycleConflictsUseStableRedactedApiErrors() throws Exception {
        Cookie admin = loginCookie("admin");
        when(lifecycle.publish(any())).thenThrow(new DocumentReplacementConflictException());
        mockMvc.perform(post("/api/admin/knowledge-documents/{id}/publish", DOCUMENT_ID)
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_REPLACEMENT_CONFLICT"))
                .andExpect(jsonPath("$.body").doesNotExist());

        when(lifecycle.archive(any())).thenThrow(new DocumentStateConflictException());
        mockMvc.perform(post("/api/admin/knowledge-documents/{id}/archive", DOCUMENT_ID).cookie(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_STATE_CONFLICT"));
    }

    /**
     * 业务目的：管理员 multipart 导入必须返回 201 完整结果，文件名和消息仅作为 JSON 文本且绝不暴露对象键。
     */
    @Test
    void adminMultipartImportReturnsSafeCompleteResult() throws Exception {
        when(scopeResolver.resolveAdmin(any(), any(), any())).thenReturn(KnowledgeScope.global());
        when(imports.importDocuments(any())).thenReturn(importBatch("<script>alert(1)</script>.md"));

        mockMvc.perform(importRequest(loginCookie("admin"), "guide.md", "body".getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.succeededCount").value(1))
                .andExpect(jsonPath("$.items[0].entryName").value("<script>alert(1)</script>.md"))
                .andExpect(jsonPath("$.items[0].message").value("<b>仅文本</b>"))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].body").doesNotExist());
        verify(imports).importDocuments(any());
    }

    /**
     * 业务目的：普通成员直接提交 multipart 必须在进入 Controller 前返回 403，不解析范围也不把正文交给导入用例。
     */
    @Test
    void memberCannotSubmitImportBody() throws Exception {
        mockMvc.perform(importRequest(loginCookie("member"), "secret.md", "secret body".getBytes()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verify(scopeResolver, never()).resolveAdmin(any(), any(), any());
        verify(imports, never()).importDocuments(any());
    }

    /**
     * 业务目的：上传上限、外层类型和 ZIP 结构失败必须分别保留 413/415/422 稳定错误码且不回显解析器信息。
     */
    @Test
    void importBoundaryFailuresUseDistinctSafeErrors() throws Exception {
        when(scopeResolver.resolveAdmin(any(), any(), any())).thenReturn(KnowledgeScope.global());
        when(imports.importDocuments(any()))
                .thenThrow(new KnowledgeImportTooLargeException())
                .thenThrow(new KnowledgeImportTypeUnsupportedException())
                .thenThrow(new KnowledgeImportArchiveInvalidException(new IllegalStateException("parser secret")));
        Cookie admin = loginCookie("admin");

        mockMvc.perform(importRequest(admin, "large.md", "body".getBytes()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMPORT_TOO_LARGE"));
        mockMvc.perform(importRequest(admin, "file.pdf", "body".getBytes()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMPORT_TYPE_UNSUPPORTED"));
        MvcResult invalid = mockMvc.perform(importRequest(admin, "broken.zip", new byte[]{'P', 'K', 3, 4}))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMPORT_ARCHIVE_INVALID"))
                .andReturn();
        assertThat(invalid.getResponse().getContentAsString()).doesNotContain("parser secret");
    }

    /**
     * 业务目的：历史批次不存在必须返回专用 404，不能退化成空结果或泄露原始对象元数据。
     */
    @Test
    void unknownImportBatchUsesDedicatedNotFoundError() throws Exception {
        Long batchId = 458202477256070486L;
        when(imports.getBatch(batchId)).thenThrow(new KnowledgeImportBatchNotFoundException());

        mockMvc.perform(get(KnowledgeDocumentImportHttpContract.BASE_PATH + "/{batchId}", batchId)
                        .cookie(loginCookie("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_IMPORT_BATCH_NOT_FOUND"))
                .andExpect(jsonPath("$.objectKey").doesNotExist());
    }

    /**
     * 业务目的：管理员提交重建必须返回 202；single-flight 复用由用例返回的同一任务 ID，Controller 不伪造新 ID。
     */
    @Test
    void adminSubmitsKnowledgeReindexWithAcceptedSingleFlightView() throws Exception {
        Long jobId = 3349842972707284583L;
        KnowledgeIndexJobView pending = new KnowledgeIndexJobView(
                jobId, JobService.Status.PENDING, 0, null, null, null);
        // 管理员手动入口提交增量刷新模式，全量重建只由刷新内部在必要时降级触发。
        when(indexJobs.submit(KnowledgeIndexJobTypes.REINDEX_MODE_REFRESH)).thenReturn(pending);

        Cookie admin = loginCookie("admin");
        mockMvc.perform(post(KnowledgeIndexJobHttpContract.BASE_PATH).cookie(admin))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post(KnowledgeIndexJobHttpContract.BASE_PATH).cookie(admin))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId.toString()));
    }

    /**
     * 业务目的：PENDING/RUNNING/SUCCEEDED/FAILED 查询必须返回持久状态，失败只暴露已脱敏摘要。
     */
    @Test
    void adminReadsAllKnowledgeIndexJobStatesWithSafeFailureSummary() throws Exception {
        Long jobId = 3349842972707284583L;
        when(indexJobs.get(jobId))
                .thenReturn(new KnowledgeIndexJobView(jobId, JobService.Status.PENDING, 0, null, null, null))
                .thenReturn(new KnowledgeIndexJobView(jobId, JobService.Status.RUNNING, 55, NOW, null, null))
                .thenReturn(new KnowledgeIndexJobView(jobId, JobService.Status.SUCCEEDED, 100, NOW, NOW, null))
                .thenReturn(new KnowledgeIndexJobView(
                        jobId, JobService.Status.FAILED, 55, NOW, NOW, "数据库操作失败 [REDACTED]"));
        Cookie admin = loginCookie("admin");

        for (String expected : List.of("PENDING", "RUNNING", "SUCCEEDED")) {
            mockMvc.perform(get(KnowledgeIndexJobHttpContract.BASE_PATH + "/{jobId}", jobId).cookie(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(expected));
        }
        mockMvc.perform(get(KnowledgeIndexJobHttpContract.BASE_PATH + "/{jobId}", jobId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureSummary").value("数据库操作失败 [REDACTED]"))
                .andExpect(jsonPath("$.ownerInstance").doesNotExist());
    }

    /**
     * 业务目的：成员不能提交重建，非知识任务 ID 必须统一 404，避免泄露其他后台任务存在性。
     */
    @Test
    void memberIsForbiddenAndNonKnowledgeJobIsNotFound() throws Exception {
        Long jobId = 6241483468158498680L;
        mockMvc.perform(post(KnowledgeIndexJobHttpContract.BASE_PATH).cookie(loginCookie("member")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        verify(indexJobs, never()).submit(anyString());

        when(indexJobs.get(jobId)).thenThrow(new io.github.loredock.knowledge.exception.KnowledgeIndexJobNotFoundException());
        mockMvc.perform(get(KnowledgeIndexJobHttpContract.BASE_PATH + "/{jobId}", jobId)
                        .cookie(loginCookie("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_INDEX_JOB_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder importRequest(
            Cookie cookie,
            String filename,
            byte[] content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                KnowledgeDocumentImportHttpContract.FILE_PART, filename, "application/octet-stream", content);
        MockMultipartFile options = new MockMultipartFile(
                KnowledgeDocumentImportHttpContract.OPTIONS_PART, "options.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"scope":{"type":"GLOBAL","project":null,"branch":null},
                         "directoryPrefix":"imports","tags":["tag"],
                         "sourceDefaults":{"type":"MANUAL","wikiUrl":null,"originalFilename":null,"curationNote":"curated"}}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return multipart(KnowledgeDocumentImportHttpContract.BASE_PATH).file(file).file(options).cookie(cookie);
    }

    private KnowledgeImportBatchView importBatch(String entryName) {
        Long batchId = 458202477256070486L;
        return new KnowledgeImportBatchView(
                batchId, entryName, KnowledgeScope.global(), "imports", ImportBatchStatus.COMPLETED,
                1, 0, 0, List.of(new KnowledgeImportItemView(
                        0, entryName, ImportItemStatus.SUCCEEDED, ImportItemReason.IMPORTED,
                        "<b>仅文本</b>", DOCUMENT_ID)), NOW, "admin");
    }

    private KnowledgeDocumentSummary summary() {
        return new KnowledgeDocumentSummary(
                DOCUMENT_ID, DocumentFormat.MARKDOWN, "Guide", "guides", List.of(DocumentTag.of("tag")),
                source(), KnowledgeScope.global(), DocumentStatus.PUBLISHED, 2,
                KnowledgeIndexSyncStatus.PENDING, NOW);
    }

    private KnowledgeDocumentView view() {
        return new KnowledgeDocumentView(
                DOCUMENT_ID, DocumentFormat.MARKDOWN, "Guide", "<script>text only</script>", "guides",
                List.of(DocumentTag.of("tag")), source(), KnowledgeScope.global(), DocumentStatus.PUBLISHED,
                new DocumentRevision(2), NOW, "admin", null, null, ReplacementLink.none(),
                KnowledgeIndexSyncStatus.PENDING, NOW.minusSeconds(10), NOW, "admin", "admin");
    }

    private DocumentSource source() {
        return new DocumentSource(
                DocumentSourceType.WIKI, "https://example.test/wiki", "source.md", "curated");
    }

    private String validWriteJson() {
        return """
                {
                  "format":"MARKDOWN",
                  "title":"Guide",
                  "body":"body",
                  "directory":"guides",
                  "tags":["tag"],
                  "source":{"type":"WIKI","wikiUrl":"https://example.test/wiki","originalFilename":"source.md","curationNote":"curated"},
                  "scope":{"type":"GLOBAL","project":null,"branch":null}
                }
                """;
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
