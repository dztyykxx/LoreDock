package io.github.loredock.project.api;

/**
 * 项目模块对其他业务模块提供的稳定范围解析契约。
 * 调用方只能依赖解析后的项目与分支标识，不得读取项目模块内部视图、实体或 Mapper。
 */
public interface ProjectService {

    /**
     * 解析启用项目及分支；分支为空时固定解析默认 main。
     *
     * @param projectIdentifier 项目业务标识
     * @param branchName 可选分支名
     * @return 已启用的稳定项目范围
     * @throws RuntimeException 项目停用或不存在、分支不存在时抛出项目模块稳定业务异常
     */
    ProjectScope resolveEnabledScope(String projectIdentifier, String branchName);

    /**
     * 解析管理入口使用的项目范围，允许返回停用项目；分支为空时只解析项目。
     *
     * @param projectIdentifier 项目业务标识
     * @param branchName 可选分支名
     * @return 稳定项目范围
     * @throws RuntimeException 项目或指定分支不存在时抛出项目模块稳定业务异常
     */
    ProjectScope resolveScope(String projectIdentifier, String branchName);

    /**
     * 按数据库主键解析管理范围，用于已持有项目与分支外键的跨模块流程。
     *
     * @param projectId 项目主键
     * @param branchId 分支主键
     * @return 稳定项目范围
     * @throws RuntimeException 项目或分支不存在、分支不属于项目时抛出项目模块稳定业务异常
     */
    ProjectScope resolveScope(Long projectId, Long branchId);
}
