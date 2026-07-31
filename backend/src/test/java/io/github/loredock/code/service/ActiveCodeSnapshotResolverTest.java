package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.ActiveCodeSnapshotView;
import io.github.loredock.project.model.result.BranchView;
import io.github.loredock.project.model.result.ProjectDetailView;
import io.github.loredock.project.service.ProjectApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActiveCodeSnapshotResolverTest {

    private static final Long PROJECT_ID = 8000000000000000061L;
    private static final Long BRANCH_ID = 8000000000000000062L;
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    /**
     * 业务目的：分支省略时必须复用项目端口的默认 main 解析，未知分支异常不得在代码层回退其他分支。
     */
    @Test
    void resolverDelegatesDefaultAndExplicitBranchSemanticsToProjectPort() {
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ActiveCodeSnapshotDataService snapshots = mock(ActiveCodeSnapshotDataService.class);
        when(projects.getEnabledProject("alpha", null)).thenReturn(project("main"));
        when(projects.getEnabledProject("alpha", "feature/a")).thenReturn(project("feature/a"));
        when(snapshots.findActive(BRANCH_ID)).thenReturn(Optional.empty());
        ActiveCodeSnapshotResolver resolver = new ActiveCodeSnapshotResolver(projects, snapshots);

        assertThat(resolver.resolve("alpha", null).branch()).isEqualTo("main");
        assertThat(resolver.resolve("alpha", "feature/a").branch()).isEqualTo("feature/a");
        verify(projects).getEnabledProject("alpha", null);
        verify(projects).getEnabledProject("alpha", "feature/a");
    }

    /**
     * 业务目的：分支没有成功激活快照时返回 NOT_INDEXED；候选由仓储强制排除，不能出现在普通状态。
     */
    @Test
    void queryReturnsNotIndexedWhenNoActiveSnapshotExists() {
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ActiveCodeSnapshotDataService snapshots = mock(ActiveCodeSnapshotDataService.class);
        when(projects.getEnabledProject("alpha", null)).thenReturn(project("main"));
        when(snapshots.findActive(BRANCH_ID)).thenReturn(Optional.empty());
        ActiveCodeSnapshotQueryService service = new ActiveCodeSnapshotQueryService(
                new ActiveCodeSnapshotResolver(projects, snapshots));

        ActiveCodeSnapshotView view = service.get("alpha", null);

        assertThat(view.status()).isEqualTo(CodeSnapshotAvailability.NOT_INDEXED);
        assertThat(view.snapshotId()).isNull();
        assertThat(view.commit()).isNull();
        assertThat(view.indexedAt()).isNull();
        assertThat(view.changeHint()).isNull();
    }

    /**
     * 业务目的：普通状态只返回一次解析固定的活动快照业务元数据，绝不暴露 generation 或物理目录。
     */
    @Test
    void queryMapsFixedActiveDescriptorAndChangeHint() {
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ActiveCodeSnapshotDataService snapshots = mock(ActiveCodeSnapshotDataService.class);
        when(projects.getEnabledProject("alpha", "main")).thenReturn(project("main"));
        Long snapshotId = 8000000000000000063L;
        when(snapshots.findActive(BRANCH_ID)).thenReturn(Optional.of(new ActiveCodeSnapshotDescriptor(
                PROJECT_ID, BRANCH_ID, snapshotId, 8000000000000000064L, "abcdef1", NOW, 7,
                CodeSnapshotChangeHint.CHANGED)));
        ActiveCodeSnapshotQueryService service = new ActiveCodeSnapshotQueryService(
                new ActiveCodeSnapshotResolver(projects, snapshots));

        ActiveCodeSnapshotView view = service.get("alpha", "main");

        assertThat(view.status()).isEqualTo(CodeSnapshotAvailability.INDEXED);
        assertThat(view.snapshotId()).isEqualTo(snapshotId);
        assertThat(view.commit()).isEqualTo("abcdef1");
        assertThat(view.indexedFileCount()).isEqualTo(7);
        assertThat(view.changeHint()).isEqualTo(CodeSnapshotChangeHint.CHANGED);
    }

    private ProjectDetailView project(String selectedBranch) {
        return new ProjectDetailView(
                PROJECT_ID, "alpha", "Alpha", "", "Java", "main", selectedBranch,
                List.of(new BranchView(BRANCH_ID, selectedBranch, NOW, NOW, "test", "test")));
    }
}
