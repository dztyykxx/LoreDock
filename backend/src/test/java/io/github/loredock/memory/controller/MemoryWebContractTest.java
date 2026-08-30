package io.github.loredock.memory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryDraftInput;
import io.github.loredock.memory.api.MemoryEditInput;
import io.github.loredock.memory.api.MemoryFull;
import io.github.loredock.memory.api.MemoryPage;
import io.github.loredock.memory.api.MemoryPageQuery;
import io.github.loredock.memory.api.MemoryRequestException;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.api.MemorySourceType;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.converter.MemoryHttpContract;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
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

/**
 * 记忆管理接口的权限与错误语义契约（行为 D，测试 ⑫-⑮ + 成功路径 5.3）。
 * Controller 只做资源路由与契约映射，业务规则（范围/项目/探测拒绝）由 {@link MemoryService} 执行，
 * 因此本测试用 mock 契约驱动：非管理员拦截 + 稳定错误码 + 探针透传 + 审计操作者来源。
 */
@WebMvcTest(
        controllers = {AuthController.class, MemoryController.class, MemoryAdminController.class},
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

        MemoryWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class MemoryWebContractTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-30T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemoryService memories;

    @BeforeEach
    void resetSessionsAndMemories() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(memories);
    }

    /**
     * 业务目的：⑫ 记忆写操作限定管理员——非管理员调用创建/编辑/停用/删除一律 403，
     * 且契约方法一个都不能被调用（拦截链在 Controller 之前拒绝，不产生或修改任何记录）；
     * 匿名请求保持与未登录不同的 401 语义。
     */
    @Test
    void nonAdminWriteIsRejectedByServerInterceptorBeforeService() throws Exception {
        Cookie member = loginCookie("member");

        mockMvc.perform(post(MemoryHttpContract.ADMIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON).cookie(member)
                        .content(createBody(MemoryScope.PROJECT, 101L, "格式偏好", "正文用三级标题")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(put(MemoryHttpContract.ADMIN_PATH + "/1")
                        .contentType(MediaType.APPLICATION_JSON).cookie(member)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(MemoryHttpContract.ADMIN_PATH + "/1/status")
                        .contentType(MediaType.APPLICATION_JSON).cookie(member)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(MemoryHttpContract.ADMIN_PATH + "/1").cookie(member))
                .andExpect(status().isForbidden());

        verify(memories, never()).create(any(MemoryDraftInput.class));
        verify(memories, never()).update(any(MemoryEditInput.class));
        verify(memories, never()).setStatus(any(Long.class), any(MemoryStatus.class), any(String.class));
        verify(memories, never()).delete(any(Long.class));

        mockMvc.perform(post(MemoryHttpContract.ADMIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MemoryScope.GLOBAL, null, "格式偏好", "正文用三级标题")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
    }

    /**
     * 业务目的：⑬ PROJECT 记忆绑定不存在或已停用项目被拒（服务层规则经 mock 契约驱动，
     * 真实判型在 MemoryServiceIT 验证）；同时字段校验失败（空标题）必须在进入业务前以
     * 400 INVALID_REQUEST 返回，非法枚举参数同样按字段错误处理，不透传内部文本。
     */
    @Test
    void invalidProjectAndInvalidFieldsAreBadRequestWithStableCodes() throws Exception {
        Cookie admin = loginCookie("admin");

        doThrow(new MemoryRequestException(MemoryRequestException.Code.MEMORY_PROJECT_INVALID, "项目不存在"))
                .when(memories).create(any(MemoryDraftInput.class));
        mockMvc.perform(post(MemoryHttpContract.ADMIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content(createBody(MemoryScope.PROJECT, 999L, "格式偏好", "正文用三级标题")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_PROJECT_INVALID"));

        mockMvc.perform(post(MemoryHttpContract.ADMIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content("{\"scope\":\"GLOBAL\",\"category\":\"STYLE\",\"title\":\"  \",\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(memories, org.mockito.Mockito.times(1)).create(any(MemoryDraftInput.class));

        mockMvc.perform(get(MemoryHttpContract.LIST_PATH)
                        .queryParam("scope", "NOT_A_SCOPE").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(memories, never()).listPage(any(MemoryPageQuery.class));
    }

    /**
     * 业务目的：⑭ 编辑不得修改范围或所属项目——请求中的 scope/projectId 探针必须原样透传给服务端
     * （服务端规则：非空即按 MEMORY_SCOPE_EDIT_FORBIDDEN 拒绝、原记录保持不变）；Controller 不得
     * 过滤、忽略或改写探针，否则客户端可绕过范围变更拦截。
     */
    @Test
    void editProbeScopeAndProjectIdAreForwardedAndRejected() throws Exception {
        Cookie admin = loginCookie("admin");
        when(memories.update(any(MemoryEditInput.class))).thenAnswer(invocation -> {
            MemoryEditInput input = invocation.getArgument(0);
            if (input.scope() != null || input.projectId() != null) {
                throw new MemoryRequestException(
                        MemoryRequestException.Code.MEMORY_SCOPE_EDIT_FORBIDDEN, "记忆范围与所属项目不可编辑");
            }
            return full(11L, MemoryScope.GLOBAL, null);
        });

        mockMvc.perform(put(MemoryHttpContract.ADMIN_PATH + "/11")
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content("{\"title\":\"新标题\",\"scope\":\"GLOBAL\",\"projectId\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_SCOPE_EDIT_FORBIDDEN"));

        ArgumentCaptor<MemoryEditInput> captured = ArgumentCaptor.forClass(MemoryEditInput.class);
        verify(memories).update(captured.capture());
        MemoryEditInput forwarded = captured.getValue();
        assertThat(forwarded.id()).isEqualTo(11L);
        assertThat(forwarded.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(forwarded.projectId()).isEqualTo(42L);
        assertThat(forwarded.title()).isEqualTo("新标题");
    }

    /**
     * 业务目的：⑮ 编辑/停用/删除不存在的记忆必须返回明确 404（MEMORY_NOT_FOUND），
     * 与字段错误 400 区分，防止客户端把「不存在」误判成参数问题而重试。
     */
    @Test
    void editOrDisableMissingMemoryReturns404() throws Exception {
        Cookie admin = loginCookie("admin");
        doThrow(new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在"))
                .when(memories).update(any(MemoryEditInput.class));
        doThrow(new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在"))
                .when(memories).setStatus(any(Long.class), any(MemoryStatus.class), any(String.class));
        doThrow(new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在"))
                .when(memories).delete(any(Long.class));

        mockMvc.perform(put(MemoryHttpContract.ADMIN_PATH + "/777")
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMORY_NOT_FOUND"));
        mockMvc.perform(patch(MemoryHttpContract.ADMIN_PATH + "/777/status")
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(MemoryHttpContract.ADMIN_PATH + "/777").cookie(admin))
                .andExpect(status().isNotFound());
    }

    /**
     * 业务目的：登录即可读取记忆列表——过滤条件（范围/分类/状态/关键词）与分页参数透传契约，
     * 返回有界事务视图（含标题/摘要/正文与审计时间），成员与管理员都只能调用读接口获得一致结果。
     */
    @Test
    void memberListsMemoriesWithFiltersAndPaging() throws Exception {
        long total = 1L;
        when(memories.listPage(any(MemoryPageQuery.class))).thenReturn(
                new MemoryPage(total, 2, 10, List.of(
                        full(5L, MemoryScope.PROJECT, 101L))));
        Cookie member = loginCookie("member");

        mockMvc.perform(get(MemoryHttpContract.LIST_PATH)
                        .queryParam("scope", "PROJECT")
                        .queryParam("category", "FORMAT")
                        .queryParam("status", "ACTIVE")
                        .queryParam("keyword", "三级标题")
                        .queryParam("page", "2")
                        .queryParam("size", "10")
                        .cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].id").value(5))
                .andExpect(jsonPath("$.items[0].scope").value("PROJECT"))
                .andExpect(jsonPath("$.items[0].projectIdentifier").value("project-101"))
                .andExpect(jsonPath("$.items[0].useCount").value(3));

        ArgumentCaptor<MemoryPageQuery> query = ArgumentCaptor.forClass(MemoryPageQuery.class);
        verify(memories).listPage(query.capture());
        MemoryPageQuery forwarded = query.getValue();
        assertThat(forwarded.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(forwarded.category()).isEqualTo(MemoryCategory.FORMAT);
        assertThat(forwarded.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(forwarded.keyword()).isEqualTo("三级标题");
        assertThat(forwarded.page()).isEqualTo(2);
        assertThat(forwarded.size()).isEqualTo(10);
    }

    /**
     * 业务目的：管理员人工创建计入审计操作者、创建返回 201 与完整视图；停用/启用与删除走
     * 各自语义端点（PATCH 状态 / DELETE 204），不把状态变更混进编辑或整体替换。
     */
    @Test
    void adminCreateStatusChangeAndDeleteSucceed() throws Exception {
        Cookie admin = loginCookie("admin");

        when(memories.create(any(MemoryDraftInput.class))).thenReturn(full(20L, MemoryScope.PROJECT, 101L));
        mockMvc.perform(post(MemoryHttpContract.ADMIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content(createBody(MemoryScope.PROJECT, 101L, "格式偏好", "正文用三级标题")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"));
        ArgumentCaptor<MemoryDraftInput> draft = ArgumentCaptor.forClass(MemoryDraftInput.class);
        verify(memories).create(draft.capture());
        assertThat(draft.getValue().scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(draft.getValue().projectId()).isEqualTo(101L);
        assertThat(draft.getValue().operatorId()).isEqualTo("admin");

        when(memories.setStatus(any(Long.class), any(MemoryStatus.class), any(String.class)))
                .thenReturn(full(20L, MemoryScope.PROJECT, 101L));
        mockMvc.perform(patch(MemoryHttpContract.ADMIN_PATH + "/20/status")
                        .contentType(MediaType.APPLICATION_JSON).cookie(admin)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(memories).setStatus(20L, MemoryStatus.DISABLED, "admin");

        mockMvc.perform(delete(MemoryHttpContract.ADMIN_PATH + "/20").cookie(admin))
                .andExpect(status().isNoContent());
        verify(memories).delete(20L);
    }

    private String createBody(MemoryScope scope, Long projectId, String title, String content) {
        return "{\"scope\":\"" + scope + "\"," + (projectId == null ? "" : "\"projectId\":" + projectId + ",")
                + "\"category\":\"STYLE\",\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
    }

    /** 测试夹具：唯一键固定、状态 ACTIVE、来源 MANUAL 的完整记忆视图。 */
    private MemoryFull full(long id, MemoryScope scope, Long projectId) {
        return new MemoryFull(id, scope, projectId,
                projectId == null ? null : "project-" + projectId,
                MemoryCategory.STYLE, "格式偏好", "正文用书面风格，三级标题组织",
                "正文用书面风格，三级标题组织，引用标注出处",
                MemoryStatus.ACTIVE, MemorySourceType.MANUAL, null, null,
                3L, AT, AT, AT);
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
