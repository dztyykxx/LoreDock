package io.github.loredock.knowledge.service.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.loredock.knowledge.exception.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectKnowledgeScopeResolverTest {

    private ProjectService projects;
    private ProjectKnowledgeScopeResolver resolver;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        resolver = new ProjectKnowledgeScopeResolver(projects);
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
        verifyNoInteractions(projects);
    }

    /**
     * 业务目的：普通项目入口必须复用项目能力的启用状态与默认 main 规则，避免知识模块复制后产生漂移。
     */
    @Test
    void browseResolvesEnabledProjectAndDefaultMainBranch() {
        Long projectId = 8000000000000000013L;
        Long mainId = 8000000000000000014L;
        when(projects.resolveEnabledScope("alpha", null)).thenReturn(project(projectId, mainId, "alpha", "main", true));

        assertThat(resolver.resolveBrowse(KnowledgeBrowseContextType.PROJECT, "alpha", null))
                .isEqualTo(new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, projectId, mainId));
        verify(projects).resolveEnabledScope("alpha", null);
    }

    /**
     * 业务目的：未知分支必须保留项目能力的 404 语义且绝不再次调用 main，防止用户请求被静默改写到其他范围。
     */
    @Test
    void unknownBrowseBranchNeverFallsBackToMain() {
        when(projects.resolveEnabledScope("alpha", "missing")).thenThrow(new BranchNotFoundException());

        assertThatThrownBy(() -> resolver.resolveBrowse(
                KnowledgeBrowseContextType.PROJECT, "alpha", "missing"))
                .isInstanceOf(BranchNotFoundException.class);
        verify(projects).resolveEnabledScope("alpha", "missing");
        verify(projects, never()).resolveEnabledScope("alpha", null);
    }

    /**
     * 业务目的：管理员可以维护停用项目知识，解析必须走管理查询并返回该项目真实 Long，而不是复用普通可见性。
     */
    @Test
    void adminScopeCanResolveDisabledProject() {
        Long projectId = 8000000000000000015L;
        when(projects.resolveScope("disabled", null))
                .thenReturn(project(projectId, null, "disabled", null, false));

        assertThat(resolver.resolveAdmin(KnowledgeScopeType.PROJECT, "disabled", null))
                .isEqualTo(KnowledgeScope.project(projectId));
        verify(projects).resolveScope("disabled", null);
    }

    /**
     * 业务目的：BRANCH 范围必须从所选项目详情中取得分支 Long，其他项目同名或未知分支不得被接受。
     */
    @Test
    void adminBranchMustBelongToSelectedProject() {
        Long projectId = 8000000000000000016L;
        Long mainId = 8000000000000000017L;
        when(projects.resolveScope("alpha", "main"))
                .thenReturn(project(projectId, mainId, "alpha", "main", false));
        when(projects.resolveScope("alpha", "other")).thenThrow(new BranchNotFoundException());

        assertThat(resolver.resolveAdmin(KnowledgeScopeType.BRANCH, "alpha", "main"))
                .isEqualTo(KnowledgeScope.branch(projectId, mainId));
        assertThatThrownBy(() -> resolver.resolveAdmin(KnowledgeScopeType.BRANCH, "alpha", "other"))
                .isInstanceOf(KnowledgeScopeInvalidException.class);
    }

    private ProjectScope project(
            Long projectId, Long branchId, String identifier, String branchName, boolean enabled
    ) {
        return new ProjectScope(projectId, identifier, identifier, enabled, branchId, branchName);
    }
}
