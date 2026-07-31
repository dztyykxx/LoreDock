package io.github.loredock.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import io.github.loredock.auth.config.IdentityRoleConfiguration;
import io.github.loredock.auth.config.IdentityWebConfiguration;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.platform.web.GlobalExceptionHandler;
import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import io.github.loredock.project.controller.AdminProjectController;
import io.github.loredock.project.controller.ProjectController;
import io.github.loredock.project.exception.ProjectIdentifierConflictException;
import io.github.loredock.project.exception.ProjectNotFoundException;
import io.github.loredock.project.model.command.AddBranchCommand;
import io.github.loredock.project.model.command.ChangeProjectStatusCommand;
import io.github.loredock.project.model.command.CreateProjectCommand;
import io.github.loredock.project.model.enums.ProjectStatus;
import io.github.loredock.project.model.result.AdminProjectDetailView;
import io.github.loredock.project.model.result.AdminProjectSummaryView;
import io.github.loredock.project.model.result.BranchView;
import io.github.loredock.project.model.result.ProjectDetailView;
import io.github.loredock.project.model.result.ProjectSummaryView;
import io.github.loredock.project.service.ProjectApplicationService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
        controllers = {AuthController.class, ProjectController.class, AdminProjectController.class},
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
        IdentityWebContractTest.StubIdentityConfiguration.class,
        SaBeanRegister.class,
        SaBeanInject.class,
        SaTokenContextRegister.class,
        GlobalExceptionHandler.class,
        PlatformConfiguration.class,
        SensitiveDataRedactor.class
})
class ProjectWebContractTest {

    private static final Long PROJECT_ID = 2891640495451214098L;
    private static final Long MAIN_ID = 5783280990902428195L;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectApplicationService projects;

    @BeforeEach
    void resetSessionsAndUseCases() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(projects);
    }

    /**
     * 业务目的：普通项目接口必须要求有效 Web 会话，未登录请求不能触达查询用例或枚举项目。
     */
    @Test
    void ordinaryProjectApiRequiresLoginBeforeQueryDispatch() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));

        verify(projects, never()).listEnabledProjects();
    }

    /**
     * 业务目的：成员绕过前端直接调用管理写接口仍须返回 403，且项目创建不能产生业务副作用。
     */
    @Test
    void memberCannotCreateProjectByCallingAdminApiDirectly() throws Exception {
        Cookie member = loginCookie("member");

        mockMvc.perform(post("/api/admin/projects")
                        .cookie(member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verify(projects, never()).createProject(any());
    }

    /**
     * 业务目的：普通列表和详情必须完整返回 T2 的真实范围字段，并把显式分支原样交给应用层解析。
     */
    @Test
    void memberCanListEnabledProjectsAndSelectExplicitBranch() throws Exception {
        when(projects.listEnabledProjects()).thenReturn(List.of(summary()));
        when(projects.getEnabledProject("network-tool", "Feature/Case")).thenReturn(detail("Feature/Case"));
        Cookie member = loginCookie("member");

        mockMvc.perform(get("/api/projects").cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$[0].identifier").value("network-tool"))
                .andExpect(jsonPath("$[0].name").value("Network Tool"))
                .andExpect(jsonPath("$[0].description").value("Description"))
                .andExpect(jsonPath("$[0].technologyStack").value("Java 21"))
                .andExpect(jsonPath("$[0].defaultBranch").value("main"))
                .andExpect(jsonPath("$[0].branchCount").value(2))
                .andExpect(jsonPath("$[0].knowledgeCount").doesNotExist())
                .andExpect(jsonPath("$[0].snapshot").doesNotExist());

        mockMvc.perform(get("/api/projects/network-tool")
                        .queryParam("branch", "Feature/Case")
                        .cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedBranch").value("Feature/Case"))
                .andExpect(jsonPath("$.branches[0].id").value(MAIN_ID.toString()))
                .andExpect(jsonPath("$.branches[0].name").value("main"))
                .andExpect(jsonPath("$.branches[0].createdAt").value("2026-07-30T00:00:00Z"))
                .andExpect(jsonPath("$.branches[0].createdBy").value("admin"));
        verify(projects).getEnabledProject("network-tool", "Feature/Case");
    }

    /**
     * 业务目的：省略 branch 查询参数必须由应用层选择默认 main，Controller 不得擅自改写或回退分支。
     */
    @Test
    void missingBranchQueryIsPassedAsDefaultSelectionRequest() throws Exception {
        when(projects.getEnabledProject("network-tool", null)).thenReturn(detail("main"));

        mockMvc.perform(get("/api/projects/network-tool").cookie(loginCookie("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultBranch").value("main"))
                .andExpect(jsonPath("$.selectedBranch").value("main"));
        verify(projects).getEnabledProject("network-tool", null);
    }

    /**
     * 业务目的：管理员创建项目、添加分支和幂等更新状态的请求/响应字段必须严格映射既有应用契约。
     */
    @Test
    void adminWriteEndpointsMapCompleteContracts() throws Exception {
        AdminProjectDetailView adminDetail = adminDetail(ProjectStatus.ENABLED);
        BranchView feature = new BranchView(
                1674921486353642292L,
                "feature/import-export", NOW, NOW, "admin", "admin");
        when(projects.createProject(any())).thenReturn(adminDetail);
        when(projects.addBranch(eq(PROJECT_ID), any())).thenReturn(feature);
        when(projects.changeStatus(eq(PROJECT_ID), any())).thenReturn(adminDetail(ProjectStatus.DISABLED));
        Cookie admin = loginCookie("admin");

        mockMvc.perform(post("/api/admin/projects")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON).content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.identifier").value("network-tool"))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.defaultBranch").value("main"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-30T00:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-07-30T00:00:00Z"))
                .andExpect(jsonPath("$.createdBy").value("admin"))
                .andExpect(jsonPath("$.updatedBy").value("admin"));
        verify(projects).createProject(new CreateProjectCommand(
                "Network Tool", "network-tool", "Description", "Java 21"));

        mockMvc.perform(post("/api/admin/projects/{id}/branches", PROJECT_ID)
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"feature/import-export\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("feature/import-export"));
        verify(projects).addBranch(PROJECT_ID, new AddBranchCommand("feature/import-export"));

        mockMvc.perform(patch("/api/admin/projects/{id}/status", PROJECT_ID)
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        verify(projects).changeStatus(PROJECT_ID, new ChangeProjectStatusCommand(ProjectStatus.DISABLED));
    }

    /**
     * 业务目的：管理查询保留停用项目及完整审计，而普通接口不能通过相同响应泄露停用状态。
     */
    @Test
    void adminQueriesReturnDisabledProjectWithAuditFields() throws Exception {
        when(projects.listProjects(ProjectStatus.DISABLED)).thenReturn(List.of(adminSummary(ProjectStatus.DISABLED)));
        when(projects.getProject(PROJECT_ID)).thenReturn(adminDetail(ProjectStatus.DISABLED));
        Cookie admin = loginCookie("admin");

        mockMvc.perform(get("/api/admin/projects").queryParam("status", "DISABLED").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DISABLED"))
                .andExpect(jsonPath("$[0].branchCount").value(1))
                .andExpect(jsonPath("$[0].createdBy").value("admin"));
        mockMvc.perform(get("/api/admin/projects/{id}", PROJECT_ID).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.branches[0].name").value("main"));
    }

    /**
     * 业务目的：无效字段、未知资源和命名冲突必须保留 400/404/409 稳定语义，且响应不泄露内部异常。
     */
    @Test
    void validationNotFoundAndConflictFailuresUseStableApiErrors() throws Exception {
        Cookie admin = loginCookie("admin");
        mockMvc.perform(post("/api/admin/projects")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"identifier\":\"Bad_Id\",\"description\":null,\"technologyStack\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(projects, never()).createProject(any());

        when(projects.getEnabledProject("missing-project", null)).thenThrow(new ProjectNotFoundException());
        mockMvc.perform(get("/api/projects/missing-project").cookie(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        when(projects.createProject(any())).thenThrow(new ProjectIdentifierConflictException());
        mockMvc.perform(post("/api/admin/projects")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON).content(validCreateJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_IDENTIFIER_CONFLICT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("uq_project_space_identifier"))));
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("loredock_session");
    }

    private String validCreateJson() {
        return "{\"name\":\"Network Tool\",\"identifier\":\"network-tool\","
                + "\"description\":\"Description\",\"technologyStack\":\"Java 21\"}";
    }

    private ProjectSummaryView summary() {
        return new ProjectSummaryView(
                PROJECT_ID, "network-tool", "Network Tool", "Description", "Java 21", "main", 2);
    }

    private ProjectDetailView detail(String selectedBranch) {
        return new ProjectDetailView(
                PROJECT_ID, "network-tool", "Network Tool", "Description", "Java 21", "main", selectedBranch,
                List.of(new BranchView(MAIN_ID, "main", NOW, NOW, "admin", "admin")));
    }

    private AdminProjectDetailView adminDetail(ProjectStatus status) {
        return new AdminProjectDetailView(
                PROJECT_ID, "network-tool", "Network Tool", "Description", "Java 21", status, "main",
                List.of(new BranchView(MAIN_ID, "main", NOW, NOW, "admin", "admin")),
                NOW, NOW, "admin", "admin");
    }

    private AdminProjectSummaryView adminSummary(ProjectStatus status) {
        return new AdminProjectSummaryView(
                PROJECT_ID, "network-tool", "Network Tool", "Description", "Java 21", status, "main", 1,
                NOW, NOW, "admin", "admin");
    }
}
