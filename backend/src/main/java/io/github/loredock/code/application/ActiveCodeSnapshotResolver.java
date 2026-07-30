package io.github.loredock.code.application;

import io.github.loredock.project.application.BranchNotFoundException;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import org.springframework.stereotype.Service;

/** 普通代码状态、搜索和片段读取共用的已启用项目与明确分支范围解析器。 */
@Service
public class ActiveCodeSnapshotResolver {

    private final ProjectQueryUseCase projects;
    private final ActiveCodeSnapshotRepository snapshots;

    /**
     * @param projects 统一负责停用项目 404、默认 main 和未知分支失败的项目端口
     * @param snapshots 只读取活动快照/generation 的仓储
     */
    public ActiveCodeSnapshotResolver(ProjectQueryUseCase projects, ActiveCodeSnapshotRepository snapshots) {
        this.projects = projects;
        this.snapshots = snapshots;
    }

    /** 一次解析固定项目、分支及活动描述符，调用期间不得再次猜测或回退范围。 */
    public ResolvedCodeSnapshotScope resolve(String projectIdentifier, String branch) {
        ProjectDetailView project = projects.getEnabledProject(projectIdentifier, branch);
        var selected = project.branches().stream()
                .filter(candidate -> candidate.name().equals(project.selectedBranch()))
                .findFirst().orElseThrow(BranchNotFoundException::new);
        return new ResolvedCodeSnapshotScope(
                project.identifier(), project.id(), selected.name(), selected.id(), snapshots.findActive(selected.id()));
    }
}
