package io.github.loredock.project.model.result;

import java.util.List;

/**
 * 普通项目详情输出；指定未知分支时用例必须失败，不能静默回退默认分支。
 *
 * @param id 项目 Long
 * @param identifier 项目业务标识
 * @param name 项目名称
 * @param description 简介
 * @param technologyStack 主要技术栈
 * @param defaultBranch 默认分支
 * @param selectedBranch 当前明确选择的分支
 * @param branches 该项目的全部可选分支
 */
public record ProjectDetailView(
        Long id,
        String identifier,
        String name,
        String description,
        String technologyStack,
        String defaultBranch,
        String selectedBranch,
        List<BranchView> branches
) {
}
