package io.github.loredock.project.application;

import io.github.loredock.project.domain.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理项目列表输出，明确保留已停用项目及审计信息。
 *
 * @param id 项目 UUID
 * @param identifier 项目标识
 * @param name 项目名称
 * @param description 简介
 * @param technologyStack 主要技术栈
 * @param status 项目状态
 * @param defaultBranch 默认分支
 * @param branchCount 真实分支数量
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @param createdBy 创建操作者
 * @param updatedBy 更新操作者
 */
public record AdminProjectSummaryView(
        UUID id,
        String identifier,
        String name,
        String description,
        String technologyStack,
        ProjectStatus status,
        String defaultBranch,
        long branchCount,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
