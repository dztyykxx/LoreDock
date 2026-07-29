package io.github.loredock.project.application;

import java.util.UUID;

/**
 * 管理员项目与分支写能力；入口层必须先完成 ADMIN 授权。
 */
public interface ProjectCommandUseCase {

    /**
     * 原子创建已启用项目及唯一默认 main 分支。该 POST 能力不承诺幂等，重复标识返回冲突。
     *
     * @param command 创建输入
     * @return 创建后的完整管理详情
     * @throws ProjectIdentifierConflictException 项目标识已存在
     */
    AdminProjectDetailView createProject(CreateProjectCommand command);

    /**
     * 在项目范围内创建分支；同项目重名返回冲突，不同项目可重名。
     *
     * @param projectId 项目 UUID
     * @param command 分支输入
     * @return 新分支
     * @throws ProjectNotFoundException 项目不存在
     * @throws BranchNameConflictException 项目内分支名已存在
     */
    BranchView addBranch(UUID projectId, AddBranchCommand command);

    /**
     * 幂等设置项目状态；停用不删除任何项目或分支数据。
     *
     * @param projectId 项目 UUID
     * @param command 目标状态
     * @return 当前完整管理详情
     * @throws ProjectNotFoundException 项目不存在
     */
    AdminProjectDetailView changeStatus(UUID projectId, ChangeProjectStatusCommand command);
}
