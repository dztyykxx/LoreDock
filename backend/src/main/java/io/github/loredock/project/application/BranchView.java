package io.github.loredock.project.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 应用层分支输出，不包含数据库实体或文件路径。
 *
 * @param id 分支 UUID
 * @param name 分支名
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @param createdBy 创建操作者
 * @param updatedBy 更新操作者
 */
public record BranchView(
        UUID id,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
