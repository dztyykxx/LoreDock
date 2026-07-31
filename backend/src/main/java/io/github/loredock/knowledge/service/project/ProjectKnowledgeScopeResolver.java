package io.github.loredock.knowledge.service.project;

import io.github.loredock.knowledge.exception.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.model.result.AdminProjectDetailView;
import io.github.loredock.project.model.result.AdminProjectSummaryView;
import io.github.loredock.project.model.result.BranchView;
import io.github.loredock.project.model.result.ProjectDetailView;
import io.github.loredock.project.service.ProjectApplicationService;
import org.springframework.stereotype.Component;

/**
 * 基于 T2 项目查询能力的知识范围适配器；普通与管理解析分别保留项目启停可见性语义。
 */
@Component
public class ProjectKnowledgeScopeResolver {

    private final ProjectApplicationService projectQueries;
    private final ProjectApplicationService adminProjectQueries;

    /**
     * @param projectQueries 只查询已启用项目的普通端口
     * @param adminProjectQueries 可查询停用项目的管理端口
     */
    public ProjectKnowledgeScopeResolver(
            ProjectApplicationService projectQueries,
            ProjectApplicationService adminProjectQueries
    ) {
        this.projectQueries = projectQueries;
        this.adminProjectQueries = adminProjectQueries;
    }

    public KnowledgeBrowseContext resolveBrowse(
            KnowledgeBrowseContextType type,
            String projectIdentifier,
            String branchName
    ) {
        if (type == null) {
            throw new KnowledgeScopeInvalidException();
        }
        if (type == KnowledgeBrowseContextType.GLOBAL) {
            requireAbsent(projectIdentifier, branchName);
            return new KnowledgeBrowseContext(type, null, null);
        }
        if (!hasText(projectIdentifier)) {
            throw new KnowledgeScopeInvalidException();
        }
        ProjectDetailView project = projectQueries.getEnabledProject(projectIdentifier.strip(), normalizeOptional(branchName));
        BranchView selectedBranch = project.branches().stream()
                .filter(branch -> branch.name().equals(project.selectedBranch()))
                .findFirst()
                .orElseThrow(BranchNotFoundException::new);
        return new KnowledgeBrowseContext(type, project.id(), selectedBranch.id());
    }

    public KnowledgeScope resolveAdmin(
            KnowledgeScopeType type,
            String projectIdentifier,
            String branchName
    ) {
        if (type == null) {
            throw new KnowledgeScopeInvalidException();
        }
        if (type == KnowledgeScopeType.GLOBAL) {
            requireAbsent(projectIdentifier, branchName);
            return KnowledgeScope.global();
        }
        if (!hasText(projectIdentifier)) {
            throw new KnowledgeScopeInvalidException();
        }
        AdminProjectSummaryView project = adminProjectQueries.listProjects(null).stream()
                .filter(candidate -> candidate.identifier().equals(projectIdentifier.strip()))
                .findFirst()
                .orElseThrow(KnowledgeScopeInvalidException::new);
        if (type == KnowledgeScopeType.PROJECT) {
            if (hasText(branchName)) {
                throw new KnowledgeScopeInvalidException();
            }
            return KnowledgeScope.project(project.id());
        }
        if (!hasText(branchName)) {
            throw new KnowledgeScopeInvalidException();
        }
        AdminProjectDetailView detail = adminProjectQueries.getProject(project.id());
        BranchView branch = detail.branches().stream()
                .filter(candidate -> candidate.name().equals(branchName.strip()))
                .findFirst()
                .orElseThrow(KnowledgeScopeInvalidException::new);
        return KnowledgeScope.branch(project.id(), branch.id());
    }

    private void requireAbsent(String projectIdentifier, String branchName) {
        if (hasText(projectIdentifier) || hasText(branchName)) {
            throw new KnowledgeScopeInvalidException();
        }
    }

    private String normalizeOptional(String value) {
        return hasText(value) ? value.strip() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
