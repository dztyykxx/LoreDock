package io.github.loredock.project.application;

import java.util.List;

/**
 * ADMIN 与 MEMBER 共用的普通项目查询，只允许数据库范围内的已启用项目。
 */
public interface ProjectQueryUseCase {

    /**
     * 按名称稳定排序查询全部已启用项目。
     *
     * @return 已启用项目摘要
     */
    List<ProjectSummaryView> listEnabledProjects();

    /**
     * 查询已启用项目并解析所选分支；分支为空时使用 main，未知分支必须失败且绝不回退。
     *
     * @param identifier 项目业务标识
     * @param branch 可选分支名，空值代表默认 main
     * @return 项目详情与明确分支选择
     * @throws ProjectNotFoundException 项目不存在或已停用
     * @throws BranchNotFoundException 指定分支不属于该项目
     */
    ProjectDetailView getEnabledProject(String identifier, String branch);
}
