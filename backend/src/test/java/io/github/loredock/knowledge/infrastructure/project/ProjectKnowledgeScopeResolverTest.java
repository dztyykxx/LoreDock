package io.github.loredock.knowledge.infrastructure.project;

import io.github.loredock.knowledge.application.KnowledgeBrowseContext;
import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import io.github.loredock.project.application.AdminProjectDetailView;
import io.github.loredock.project.application.AdminProjectQueryUseCase;
import io.github.loredock.project.application.AdminProjectSummaryView;
import io.github.loredock.project.application.BranchNotFoundException;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.project.domain.ProjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectKnowledgeScopeResolverTest {

    private ProjectQueryUseCase projectQueries;
    private AdminProjectQueryUseCase adminProjectQueries;
    private ProjectKnowledgeScopeResolver resolver;

    @BeforeEach
    void setUp() {
        projectQueries = mock(ProjectQueryUseCase.class);
        adminProjectQueries = mock(AdminProjectQueryUseCase.class);
        resolver = new ProjectKnowledgeScopeResolver(projectQueries, adminProjectQueries);
    }

    /**
     * 业务目的：GLOBAL 范围与项目主数据无关，解析时不得误访问项目端口或接受残留项目字段。
     */
    @Test
    void globalScopesDoNotAccessProjectPortsAndRejectResidualFields() {
        assertThat(resolver.resolveBrowse(KnowledgeBrowseContextType.GLOBAL, null, null))
                .isEqualTo(new KnowledgeBrowseContext(KnowledgeBrowseContextType.GLOBAL, null, null));
        assertThat(resolver.resolveAdmin(KnowledgeScopeType.GLOBAL, null, null)).isEqualTo(KnowledgeScope.global());
        assertThatThrownBy(() -> resolver.resolveAdmin(KnowledgeScopeType.GLOBAL, "project", null))
                .isInstanceOf(KnowledgeScopeInvalidException.class);
        verifyNoInteractions(projectQueries, adminProjectQueries);
    }

    /**
     * 业务目的：普通项目入口必须复用项目能力的启用状态与默认 main 规则，避免知识模块复制后产生漂移。
     */
    @Test
    void browseResolvesEnabledProjectAndDefaultMainBranch() {
        UUID projectId = UUID.randomUUID();
        UUID mainId = UUID.randomUUID();
        when(projectQueries.getEnabledProject("alpha", null)).thenReturn(projectDetail(projectId, mainId, "main"));

        assertThat(resolver.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null))
                .isEqualTo(new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, projectId, mainId));
        verify(projectQueries).getEnabledProject("alpha", null);
        verifyNoInteractions(adminProjectQueries);
    }

    /**
     * 业务目的：未知分支必须保留项目能力的 404 语义且绝不再次调用 main，防止用户请求被静默改写到其他范围。
     */
    @Test
    void unknownBrowseBranchNeverFallsBackToMain() {
        when(projectQueries.getEnabledProject("alpha", "missing")).thenThrow(new BranchNotFoundException());

        assertThatThrownBy(() -> resolver.resolveBrowse(
                KnowledgeBrowseContextType.PROJECT, "alpha", "missing"))
                .isInstanceOf(BranchNotFoundException.class);
        verify(projectQueries).getEnabledProject("alpha", "missing");
        verify(projectQueries, never()).getEnabledProject("alpha", null);
    }

    /**
     * 业务目的：管理员可以维护停用项目知识，解析必须走管理查询并返回该项目真实 UUID，而不是复用普通可见性。
     */
    @Test
    void adminScopeCanResolveDisabledProject() {
        UUID projectId = UUID.randomUUID();
        when(adminProjectQueries.listProjects(null)).thenReturn(List.of(projectSummary(projectId, "disabled")));
        when(adminProjectQueries.getProject(projectId)).thenReturn(adminDetail(projectId, List.of()));

        assertThat(resolver.resolveAdmin(KnowledgeScopeType.PROJECT, "disabled", null))
                .isEqualTo(KnowledgeScope.project(projectId));
        verifyNoInteractions(projectQueries);
    }

    /**
     * 业务目的：BRANCH 范围必须从所选项目详情中取得分支 UUID，其他项目同名或未知分支不得被接受。
     */
    @Test
    void adminBranchMustBelongToSelectedProject() {
        UUID projectId = UUID.randomUUID();
        UUID mainId = UUID.randomUUID();
        when(adminProjectQueries.listProjects(null)).thenReturn(List.of(projectSummary(projectId, "alpha")));
        when(adminProjectQueries.getProject(projectId)).thenReturn(adminDetail(
                projectId, List.of(branch(mainId, "main"))));

        assertThat(resolver.resolveAdmin(KnowledgeScopeType.BRANCH, "alpha", "main"))
                .isEqualTo(KnowledgeScope.branch(projectId, mainId));
        assertThatThrownBy(() -> resolver.resolveAdmin(KnowledgeScopeType.BRANCH, "alpha", "other"))
                .isInstanceOf(KnowledgeScopeInvalidException.class);
    }

    private ProjectDetailView projectDetail(UUID projectId, UUID branchId, String selectedBranch) {
        return new ProjectDetailView(
                projectId, "alpha", "Alpha", "", "", "main", selectedBranch,
                List.of(branch(branchId, selectedBranch)));
    }

    private AdminProjectSummaryView projectSummary(UUID projectId, String identifier) {
        return new AdminProjectSummaryView(
                projectId, identifier, identifier, "", "", ProjectStatus.DISABLED, "main", 1,
                Instant.EPOCH, Instant.EPOCH, "SYSTEM", "SYSTEM");
    }

    private AdminProjectDetailView adminDetail(UUID projectId, List<BranchView> branches) {
        return new AdminProjectDetailView(
                projectId, "alpha", "Alpha", "", "", ProjectStatus.DISABLED, "main", branches,
                Instant.EPOCH, Instant.EPOCH, "SYSTEM", "SYSTEM");
    }

    private BranchView branch(UUID id, String name) {
        return new BranchView(id, name, Instant.EPOCH, Instant.EPOCH, "SYSTEM", "SYSTEM");
    }
}
