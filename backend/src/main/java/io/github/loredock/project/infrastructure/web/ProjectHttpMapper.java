package io.github.loredock.project.infrastructure.web;

import io.github.loredock.project.application.AdminProjectDetailView;
import io.github.loredock.project.application.AdminProjectSummaryView;
import io.github.loredock.project.application.BranchView;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectSummaryView;

/**
 * 项目应用 DTO 到 HTTP 响应的纯映射；不判断权限、状态或分支范围。
 */
final class ProjectHttpMapper {

    private ProjectHttpMapper() {
    }

    static ProjectSummaryResponse toResponse(ProjectSummaryView view) {
        return new ProjectSummaryResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(),
                view.defaultBranch(), view.branchCount());
    }

    static ProjectDetailResponse toResponse(ProjectDetailView view) {
        return new ProjectDetailResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(),
                view.defaultBranch(), view.selectedBranch(), view.branches().stream().map(ProjectHttpMapper::toResponse).toList());
    }

    static AdminProjectSummaryResponse toResponse(AdminProjectSummaryView view) {
        return new AdminProjectSummaryResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(), view.status(),
                view.defaultBranch(), view.branchCount(), view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }

    static AdminProjectDetailResponse toResponse(AdminProjectDetailView view) {
        return new AdminProjectDetailResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(), view.status(),
                view.defaultBranch(), view.branches().stream().map(ProjectHttpMapper::toResponse).toList(),
                view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }

    static BranchResponse toResponse(BranchView view) {
        return new BranchResponse(
                view.id(), view.name(), view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }
}
