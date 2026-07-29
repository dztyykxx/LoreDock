package io.github.loredock.project.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目分支持久化端口，所有查找都必须携带项目范围。
 */
public interface BranchRepository {

    /** @param branch 待新增分支 */
    void insert(BranchData branch);

    /** @param projectId 项目 UUID @return 项目内分支 */
    List<BranchData> findAllByProjectId(UUID projectId);

    /** @param projectId 项目 UUID @param name 分支名 @return 仅该项目内的匹配分支 */
    Optional<BranchData> findByProjectIdAndName(UUID projectId, String name);

    /** @param projectId 项目 UUID @return 项目真实分支数量 */
    long countByProjectId(UUID projectId);
}
