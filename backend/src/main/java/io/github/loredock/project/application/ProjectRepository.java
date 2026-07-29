package io.github.loredock.project.application;

import io.github.loredock.project.domain.ProjectStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目持久化端口；普通查询方法在数据源查询阶段强制限制 ENABLED，不能先跨状态加载再隐藏。
 */
public interface ProjectRepository {

    /** @param project 待新增项目 */
    void insert(ProjectData project);

    /** @param project 已更新项目状态与审计信息 */
    void update(ProjectData project);

    /** @param projectId 项目 UUID @return 任意状态项目 */
    Optional<ProjectData> findById(UUID projectId);

    /** @param identifier 项目标识 @return 仅已启用项目 */
    Optional<ProjectData> findEnabledByIdentifier(String identifier);

    /** @return 按名称稳定排序的已启用项目 */
    List<ProjectData> findAllEnabled();

    /** @param status 可选状态，空值表示全部 @return 管理项目集合 */
    List<ProjectData> findAll(ProjectStatus status);
}
