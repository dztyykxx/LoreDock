package io.github.loredock.project.model.response;

import java.util.List;

/**
 * 普通项目详情响应，明确返回当前选择与全部项目内分支。
 *
 * @param id 项目 Long
 * @param identifier 项目标识
 * @param name 项目名称
 * @param description 简介
 * @param technologyStack 主要技术栈
 * @param defaultBranch 默认分支
 * @param selectedBranch 当前选择分支
 * @param branches 项目内可选分支
 */
public record ProjectDetailResponse(
        Long id,
        String identifier,
        String name,
        String description,
        String technologyStack,
        String defaultBranch,
        String selectedBranch,
        List<BranchResponse> branches
) {
}
