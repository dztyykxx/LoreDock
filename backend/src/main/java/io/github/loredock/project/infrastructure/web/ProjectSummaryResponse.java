package io.github.loredock.project.infrastructure.web;

import java.util.UUID;

/**
 * 普通项目列表响应，只包含已启用项目的真实字段，不预留虚假知识数或快照状态。
 *
 * @param id 项目 UUID
 * @param identifier 项目标识
 * @param name 项目名称
 * @param description 简介
 * @param technologyStack 主要技术栈
 * @param defaultBranch 默认 main 分支
 * @param branchCount 真实分支数量
 */
public record ProjectSummaryResponse(
        UUID id,
        String identifier,
        String name,
        String description,
        String technologyStack,
        String defaultBranch,
        long branchCount
) {
}
