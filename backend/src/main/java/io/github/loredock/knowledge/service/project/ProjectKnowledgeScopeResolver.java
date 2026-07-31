package io.github.loredock.knowledge.service.project;

import io.github.loredock.knowledge.exception.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import org.springframework.stereotype.Component;

/**
 * 基于 T2 项目查询能力的知识范围适配器；普通与管理解析分别保留项目启停可见性语义。
 */
@Component
public class ProjectKnowledgeScopeResolver {

    private final ProjectService projects;

    /**
     * @param projects 项目模块统一的普通与管理范围契约
     */
    public ProjectKnowledgeScopeResolver(ProjectService projects) {
        this.projects = projects;
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
        ProjectScope project = projects.resolveEnabledScope(projectIdentifier.strip(), normalizeOptional(branchName));
        return new KnowledgeBrowseContext(type, project.projectId(), project.branchId());
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
        if (type == KnowledgeScopeType.PROJECT) {
            if (hasText(branchName)) {
                throw new KnowledgeScopeInvalidException();
            }
            return KnowledgeScope.project(resolveAdminScope(projectIdentifier, null).projectId());
        }
        if (!hasText(branchName)) {
            throw new KnowledgeScopeInvalidException();
        }
        ProjectScope project = resolveAdminScope(projectIdentifier, branchName.strip());
        return KnowledgeScope.branch(project.projectId(), project.branchId());
    }

    private ProjectScope resolveAdminScope(String projectIdentifier, String branchName) {
        try {
            return projects.resolveScope(projectIdentifier.strip(), branchName);
        } catch (RuntimeException exception) {
            throw new KnowledgeScopeInvalidException();
        }
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
