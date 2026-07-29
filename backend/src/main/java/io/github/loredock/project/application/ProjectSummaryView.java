package io.github.loredock.project.application;

import java.util.UUID;

/**
 * 普通项目列表输出，只承载已启用项目的真实主数据。
 *
 * @param id 项目 UUID
 * @param identifier 项目业务标识
 * @param name 项目名称
 * @param description 简介
 * @param technologyStack 主要技术栈
 * @param defaultBranch 默认分支，T2 固定为 main
 * @param branchCount 真实分支数量
 */
public record ProjectSummaryView(
        UUID id,
        String identifier,
        String name,
        String description,
        String technologyStack,
        String defaultBranch,
        long branchCount
) {
}
