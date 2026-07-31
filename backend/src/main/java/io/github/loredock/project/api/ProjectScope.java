package io.github.loredock.project.api;

/**
 * 跨模块使用的稳定项目范围，不暴露项目持久化实体、管理视图或审计字段。
 *
 * @param projectId 项目主键
 * @param projectIdentifier 项目业务标识
 * @param projectName 项目名称
 * @param enabled 项目是否启用
 * @param branchId 已解析分支主键；仅解析项目级管理范围时为空
 * @param branchName 已解析分支名称；仅解析项目级管理范围时为空
 */
public record ProjectScope(
        Long projectId,
        String projectIdentifier,
        String projectName,
        boolean enabled,
        Long branchId,
        String branchName
) {
}
