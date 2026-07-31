package io.github.loredock.code.service;

import io.github.loredock.code.model.result.ResolvedCodeSnapshotScope;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import org.springframework.stereotype.Service;

/** 普通代码状态、搜索和片段读取共用的已启用项目与明确分支范围解析器。 */
@Service
public class ActiveCodeSnapshotResolver {

    private final ProjectService projects;
    private final ActiveCodeSnapshotDataService snapshots;

    /**
     * @param projects 统一负责停用项目 404、默认 main 和未知分支失败的项目端口
     * @param snapshots 只读取活动快照/generation 的仓储
     */
    public ActiveCodeSnapshotResolver(ProjectService projects, ActiveCodeSnapshotDataService snapshots) {
        this.projects = projects;
        this.snapshots = snapshots;
    }

    /** 一次解析固定项目、分支及活动描述符，调用期间不得再次猜测或回退范围。 */
    public ResolvedCodeSnapshotScope resolve(String projectIdentifier, String branch) {
        ProjectScope project = projects.resolveEnabledScope(projectIdentifier, branch);
        return new ResolvedCodeSnapshotScope(
                project.projectIdentifier(), project.projectId(), project.branchName(), project.branchId(),
                snapshots.findActive(project.branchId()));
    }
}
