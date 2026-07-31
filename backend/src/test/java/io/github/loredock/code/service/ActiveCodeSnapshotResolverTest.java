package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.ActiveCodeSnapshotView;
import io.github.loredock.code.service.index.LuceneCodeIndexSearcher;
import io.github.loredock.code.service.index.LuceneCodeSnippetReader;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.time.Instant;
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
        ProjectService projects = mock(ProjectService.class);
        ActiveCodeSnapshotDataService snapshots = mock(ActiveCodeSnapshotDataService.class);
        when(projects.resolveEnabledScope("alpha", null)).thenReturn(project("main"));
        when(projects.resolveEnabledScope("alpha", "feature/a")).thenReturn(project("feature/a"));
        when(snapshots.findActive(BRANCH_ID)).thenReturn(Optional.empty());
        ActiveCodeSnapshotResolver resolver = new ActiveCodeSnapshotResolver(projects, snapshots);

        assertThat(resolver.resolve("alpha", null).branch()).isEqualTo("main");
        assertThat(resolver.resolve("alpha", "feature/a").branch()).isEqualTo("feature/a");
        verify(projects).resolveEnabledScope("alpha", null);
        verify(projects).resolveEnabledScope("alpha", "feature/a");
    }

    /**
     * 业务目的：分支没有成功激活快照时返回 NOT_INDEXED；候选由仓储强制排除，不能出现在普通状态。
     */
    @Test
    void queryReturnsNotIndexedWhenNoActiveSnapshotExists() {
        ProjectService projects = mock(ProjectService.class);
        ActiveCodeSnapshotDataService snapshots = mock(ActiveCodeSnapshotDataService.class);
        when(projects.resolveEnabledScope("alpha", null)).thenReturn(project("main"));
        when(snapshots.findActive(BRANCH_ID)).thenReturn(Optional.empty());
        CodeQueryServiceImpl service = new CodeQueryServiceImpl(
                new ActiveCodeSnapshotResolver(projects, snapshots),
                mock(LuceneCodeIndexSearcher.class), mock(LuceneCodeSnippetReader.class));

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
        ProjectService projects = mock(ProjectService.class);
        ActiveCodeSnapshotDataService snapshots = mock(ActiveCodeSnapshotDataService.class);
        when(projects.resolveEnabledScope("alpha", "main")).thenReturn(project("main"));
        Long snapshotId = 8000000000000000063L;
        when(snapshots.findActive(BRANCH_ID)).thenReturn(Optional.of(new ActiveCodeSnapshotDescriptor(
                PROJECT_ID, BRANCH_ID, snapshotId, 8000000000000000064L, "abcdef1", NOW, 7,
                CodeSnapshotChangeHint.CHANGED)));
        CodeQueryServiceImpl service = new CodeQueryServiceImpl(
                new ActiveCodeSnapshotResolver(projects, snapshots),
                mock(LuceneCodeIndexSearcher.class), mock(LuceneCodeSnippetReader.class));

        ActiveCodeSnapshotView view = service.get("alpha", "main");

        assertThat(view.status()).isEqualTo(CodeSnapshotAvailability.INDEXED);
        assertThat(view.snapshotId()).isEqualTo(snapshotId);
        assertThat(view.commit()).isEqualTo("abcdef1");
        assertThat(view.indexedFileCount()).isEqualTo(7);
        assertThat(view.changeHint()).isEqualTo(CodeSnapshotChangeHint.CHANGED);
    }

    private ProjectScope project(String selectedBranch) {
        return new ProjectScope(PROJECT_ID, "alpha", "Alpha", true, BRANCH_ID, selectedBranch);
    }
}
