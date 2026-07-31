package io.github.loredock.project.model.result;

/**
 * 显式演示数据准备结果，供启动日志或验收脚本区分新建与复用。
 *
 * @param createdProjects 新建项目数
 * @param reusedProjects 复用项目数
 * @param createdBranches 新建分支数，包含随项目创建的 main
 * @param reusedBranches 复用分支数，包含既有 main
 */
public record DemoPreparationReport(
        int createdProjects,
        int reusedProjects,
        int createdBranches,
        int reusedBranches
) {
}
