package io.github.loredock.project.domain;

import java.util.List;
import java.util.Objects;

/**
 * 只承载项目范围不变量的不可变领域模型：稳定标识、生命周期和分支选择。
 *
 * @param identifier 稳定项目标识
 * @param status 生命周期状态
 * @param branches 保留大小写的项目分支集合
 */
public record Project(ProjectIdentifier identifier, ProjectStatus status, List<BranchName> branches) {

    public Project {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(status, "status");
        branches = List.copyOf(branches);
        if (branches.stream().noneMatch(branch -> branch.value().equals(ProjectDefaults.DEFAULT_BRANCH))) {
            throw new IllegalArgumentException("default branch missing");
        }
    }

    /**
     * 创建启用项目及唯一默认 main 分支。
     *
     * @param identifier 已校验项目标识
     * @return 新项目领域状态
     */
    public static Project create(ProjectIdentifier identifier) {
        return new Project(identifier, ProjectStatus.ENABLED, List.of(BranchName.of(ProjectDefaults.DEFAULT_BRANCH)));
    }

    /**
     * 从持久化状态恢复项目，仍校验默认分支不变量。
     *
     * @param identifier 稳定项目标识
     * @param status 项目状态
     * @param branches 现有分支
     * @return 恢复后的项目
     */
    public static Project restore(ProjectIdentifier identifier, ProjectStatus status, List<BranchName> branches) {
        return new Project(identifier, status, branches);
    }

    /**
     * 幂等设置生命周期；停用仅替换状态，项目身份与分支集合原样保留。
     *
     * @param target 目标状态
     * @return 状态更新后的项目；相同状态时返回当前实例
     */
    public Project withStatus(ProjectStatus target) {
        Objects.requireNonNull(target, "target");
        return target == status ? this : new Project(identifier, target, branches);
    }

    /**
     * 解析当前项目内分支。未提供选择时使用 main，显式未知分支必须失败。
     *
     * @param requestedName 可为空的显式选择
     * @return 当前项目中的匹配分支
     * @throws UnknownBranchException 显式或默认分支不在当前项目
     */
    public BranchName selectBranch(String requestedName) {
        BranchName requested = BranchName.of(requestedName == null ? ProjectDefaults.DEFAULT_BRANCH : requestedName);
        return branches.stream()
                .filter(branch -> branch.equals(requested))
                .findFirst()
                .orElseThrow(UnknownBranchException::new);
    }
}
