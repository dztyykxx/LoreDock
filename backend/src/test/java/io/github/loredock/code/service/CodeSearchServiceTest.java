package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.model.enums.CodeSearchTarget;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.request.CodeSearchQuery;
import io.github.loredock.code.model.response.CodeSearchResponse;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.CodeIndexSearchHit;
import io.github.loredock.code.model.result.ResolvedCodeSnapshotScope;
import io.github.loredock.code.service.index.LuceneCodeIndexSearcher;
import io.github.loredock.code.service.index.LuceneCodeSnippetReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodeSearchServiceTest {

    /**
     * 业务目的：跨模块调用方必须通过统一 code.api 契约取得快照、搜索和片段，不能再注入三个内部 Service。
     */
    @Test
    void codeQueriesExposeOneStableCrossModuleContract() {
        ActiveCodeSnapshotResolver resolver = mock(ActiveCodeSnapshotResolver.class);
        CodeQueryServiceImpl service = new CodeQueryServiceImpl(
                resolver, mock(LuceneCodeIndexSearcher.class), mock(LuceneCodeSnippetReader.class));

        assertThat(service).isInstanceOf(io.github.loredock.code.api.CodeQueryService.class);
        System.out.println("测试证据：场景=代码查询跨模块契约，活动快照/搜索/片段由单一code.api提供");
    }

    /**
     * 业务目的：公开搜索契约必须复核固定 snapshot/commit，并只返回同一项目分支活动索引中的仓库相对路径。
     */
    @Test
    void publicSearchContractPinsActiveSnapshotAndScope() {
        ActiveCodeSnapshotResolver resolver = mock(ActiveCodeSnapshotResolver.class);
        LuceneCodeIndexSearcher index = mock(LuceneCodeIndexSearcher.class);
        ActiveCodeSnapshotDescriptor active = active();
        when(resolver.resolve("alpha", "main")).thenReturn(new ResolvedCodeSnapshotScope(
                "alpha", active.projectId(), "main", active.branchId(), Optional.of(active)));
        when(index.search(active, "userService", CodeSearchTarget.ALL, "src", 5))
                .thenReturn(List.of(new CodeIndexSearchHit(
                        "src/UserService.java", "class UserService", 3f, false)));
        io.github.loredock.code.api.CodeQueryService service = new CodeQueryServiceImpl(
                resolver, index, mock(LuceneCodeSnippetReader.class));

        var response = service.search(new io.github.loredock.code.api.CodeQuery(
                "alpha", "main", "userService", io.github.loredock.code.api.CodeQuery.Target.ALL,
                "src", 5, active.snapshotId(), active.commit()));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.projectIdentifier()).isEqualTo("alpha");
            assertThat(item.branch()).isEqualTo("main");
            assertThat(item.snapshotId()).isEqualTo(active.snapshotId());
            assertThat(item.commit()).isEqualTo(active.commit());
            assertThat(item.path()).isEqualTo("src/UserService.java");
        });
        System.out.printf("测试证据：场景=公开代码范围搜索，项目=alpha，分支=main，snapshot=%s，结果数=%d%n",
                active.snapshotId(), response.items().size());
    }

    /**
     * 业务目的：查询一次固定活动描述符并原样标注同一 commit，空命中不得扩大到候选、历史或其他分支。
     */
    @Test
    void searchUsesSingleResolvedDescriptorAndMapsStableSource() {
        ActiveCodeSnapshotResolver resolver = mock(ActiveCodeSnapshotResolver.class);
        LuceneCodeIndexSearcher index = mock(LuceneCodeIndexSearcher.class);
        ActiveCodeSnapshotDescriptor active = active();
        when(resolver.resolve("alpha", null)).thenReturn(new ResolvedCodeSnapshotScope(
                "alpha", active.projectId(), "main", active.branchId(), Optional.of(active)));
        when(index.search(active, "userService", CodeSearchTarget.ALL, "src", 10))
                .thenReturn(List.of(new CodeIndexSearchHit("src/UserService.java", "class UserService", 3f, true)));

        CodeSearchResponse response = new CodeQueryServiceImpl(
                resolver, index, mock(LuceneCodeSnippetReader.class)).search(
                new CodeSearchQuery("alpha", null, " userService ", null, "src/", null));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.commit()).isEqualTo("abcdef1");
            assertThat(item.snapshotId()).isEqualTo(active.snapshotId());
            assertThat(item.path()).isEqualTo("src/UserService.java");
        });
        verify(index).search(active, "userService", CodeSearchTarget.ALL, "src", 10);
    }

    /**
     * 业务目的：无活动快照必须明确失败且不回退；查询长度、limit 和路径前缀越界必须在访问 Lucene 前拒绝。
     */
    @Test
    void missingSnapshotAndInvalidInputsFailWithoutFallback() {
        ActiveCodeSnapshotResolver resolver = mock(ActiveCodeSnapshotResolver.class);
        LuceneCodeIndexSearcher index = mock(LuceneCodeIndexSearcher.class);
        when(resolver.resolve("alpha", null)).thenReturn(new ResolvedCodeSnapshotScope(
                "alpha", 8000000000000000070L, "main", 8000000000000000071L, Optional.empty()));
        CodeQueryServiceImpl service = new CodeQueryServiceImpl(
                resolver, index, mock(LuceneCodeSnippetReader.class));

        assertThatThrownBy(() -> service.search(new CodeSearchQuery("alpha", null, "term", null, null, null)))
                .isInstanceOf(CodeSnapshotNotFoundException.class);
        assertThatThrownBy(() -> service.search(new CodeSearchQuery("alpha", null, " ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new CodeSearchQuery("alpha", null, "x".repeat(201), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(new CodeSearchQuery("alpha", null, "term", null, "../secret", 51)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ActiveCodeSnapshotDescriptor active() {
        return new ActiveCodeSnapshotDescriptor(
                8000000000000000072L, 8000000000000000073L, 8000000000000000074L, 8000000000000000075L, "abcdef1",
                Instant.parse("2026-07-30T08:00:00Z"), 2, CodeSnapshotChangeHint.INITIAL);
    }
}
