package io.github.loredock.project.application;

import io.github.loredock.project.domain.ProjectStatus;

import java.util.List;
import java.util.UUID;

/**
 * 管理员项目查询，保留已启用与已停用状态。
 */
public interface AdminProjectQueryUseCase {

    /**
     * 查询全部管理项目，可按明确状态过滤。
     *
     * @param status 可选状态；空值表示全部
     * @return 管理项目摘要
     */
    List<AdminProjectSummaryView> listProjects(ProjectStatus status);

    /**
     * @param projectId 项目 UUID
     * @return 包含全部分支的管理详情
     * @throws ProjectNotFoundException 项目不存在
     */
    AdminProjectDetailView getProject(UUID projectId);
}
