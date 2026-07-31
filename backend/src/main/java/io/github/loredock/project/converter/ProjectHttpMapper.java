package io.github.loredock.project.converter;

import io.github.loredock.project.model.response.AdminProjectDetailResponse;
import io.github.loredock.project.model.response.AdminProjectSummaryResponse;
import io.github.loredock.project.model.response.BranchResponse;
import io.github.loredock.project.model.response.ProjectDetailResponse;
import io.github.loredock.project.model.response.ProjectSummaryResponse;
import io.github.loredock.project.model.result.AdminProjectDetailView;
import io.github.loredock.project.model.result.AdminProjectSummaryView;
import io.github.loredock.project.model.result.BranchView;
import io.github.loredock.project.model.result.ProjectDetailView;
import io.github.loredock.project.model.result.ProjectSummaryView;

/**
 * 项目应用 DTO 到 HTTP 响应的纯映射；不判断权限、状态或分支范围。
 */
public final class ProjectHttpMapper {

    private ProjectHttpMapper() {
    }

    public static ProjectSummaryResponse toResponse(ProjectSummaryView view) {
        return new ProjectSummaryResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(),
                view.defaultBranch(), view.branchCount());
    }

    public static ProjectDetailResponse toResponse(ProjectDetailView view) {
        return new ProjectDetailResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(),
                view.defaultBranch(), view.selectedBranch(), view.branches().stream().map(ProjectHttpMapper::toResponse).toList());
    }

    public static AdminProjectSummaryResponse toResponse(AdminProjectSummaryView view) {
        return new AdminProjectSummaryResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(), view.status(),
                view.defaultBranch(), view.branchCount(), view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }

    public static AdminProjectDetailResponse toResponse(AdminProjectDetailView view) {
        return new AdminProjectDetailResponse(
                view.id(), view.identifier(), view.name(), view.description(), view.technologyStack(), view.status(),
                view.defaultBranch(), view.branches().stream().map(ProjectHttpMapper::toResponse).toList(),
                view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }

    public static BranchResponse toResponse(BranchView view) {
        return new BranchResponse(
                view.id(), view.name(), view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }
}
