package io.github.loredock.project.model.result;

import java.time.Instant;

/**
 * 分支仓储端口传递的持久化无关状态；后续内部隔离使用项目 Long 与分支 Long，不使用名称拼接路径。
 *
 * @param id 分支 Long
 * @param projectId 所属项目 Long
 * @param name 保留大小写的分支名
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @param createdBy 创建操作者
 * @param updatedBy 更新操作者
 */
public record BranchData(
        Long id,
        Long projectId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
