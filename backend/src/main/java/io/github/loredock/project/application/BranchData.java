package io.github.loredock.project.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 分支仓储端口传递的持久化无关状态；后续内部隔离使用项目 UUID 与分支 UUID，不使用名称拼接路径。
 *
 * @param id 分支 UUID
 * @param projectId 所属项目 UUID
 * @param name 保留大小写的分支名
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @param createdBy 创建操作者
 * @param updatedBy 更新操作者
 */
public record BranchData(
        UUID id,
        UUID projectId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
