package io.github.loredock.code.service;

import io.github.loredock.code.api.CodeExcerpt;
import io.github.loredock.code.api.CodeExcerptQuery;
import io.github.loredock.code.api.CodeMatches;
import io.github.loredock.code.api.CodeQuery;
import io.github.loredock.code.api.CodeQueryService;
import io.github.loredock.code.api.ActiveCodeState;
import io.github.loredock.code.api.CodeSnapshotVersionChangedException;
import io.github.loredock.code.exception.CodeFileNotFoundException;
import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.exception.CodeSnippetRangeInvalidException;
import io.github.loredock.code.model.RepositoryRelativePath;
import io.github.loredock.code.model.enums.CodeSearchTarget;
import io.github.loredock.code.model.enums.CodeSnapshotAvailability;
import io.github.loredock.code.model.request.CodeSearchQuery;
import io.github.loredock.code.model.request.CodeSnippetQuery;
import io.github.loredock.code.model.response.CodeSearchResponse;
import io.github.loredock.code.model.response.CodeSnippetResponse;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.ActiveCodeSnapshotView;
import io.github.loredock.code.model.result.CodeSearchResult;
import io.github.loredock.code.model.result.ResolvedCodeSnapshotScope;
import io.github.loredock.code.service.index.LuceneCodeIndexSearcher;
import io.github.loredock.code.service.index.LuceneCodeSnippetReader;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 代码查询统一实现：每次调用先解析唯一活动快照，在同一范围完成 Lucene 搜索或 StoredField 片段读取；
 * 跨模块入口额外复核调用方固定的 snapshot/commit，HTTP 入口继续复用相同内部逻辑。
 */
@Service
public class CodeQueryServiceImpl implements CodeQueryService {

    private final ActiveCodeSnapshotResolver resolver;
    private final LuceneCodeIndexSearcher searchIndex;
    private final LuceneCodeSnippetReader snippetIndex;

    /**
     * @param resolver 已启用项目活动范围解析器
     * @param searchIndex 固定 generation 的 Lucene 查询能力
     * @param snippetIndex 精确 StoredField 读取能力
     */
    public CodeQueryServiceImpl(
            ActiveCodeSnapshotResolver resolver,
            LuceneCodeIndexSearcher searchIndex,
            LuceneCodeSnippetReader snippetIndex
    ) {
        this.resolver = resolver;
        this.searchIndex = searchIndex;
        this.snippetIndex = snippetIndex;
    }

    @Override
    public ActiveCodeState getActiveSnapshot(String projectIdentifier, String branch) {
        return toApiSnapshot(get(projectIdentifier, branch));
    }

    @Override
    public CodeMatches search(CodeQuery query) {
        Objects.requireNonNull(query, "code query is required");
        requireVersion(query.projectIdentifier(), query.branch(), query.snapshotId(), query.commit());
        CodeSearchResponse response = search(new CodeSearchQuery(
                query.projectIdentifier(), query.branch(), query.query(),
                query.target() == null ? null : CodeSearchTarget.valueOf(query.target().name()),
                query.pathPrefix(), query.limit()));
        if (response.items().stream().anyMatch(item -> !matchesVersion(item, query.snapshotId(), query.commit()))) {
            throw new CodeSnapshotVersionChangedException();
        }
        requireVersion(query.projectIdentifier(), query.branch(), query.snapshotId(), query.commit());
        return new CodeMatches(response.items().stream().map(this::toApiMatch).toList());
    }

    @Override
    public CodeExcerpt read(CodeExcerptQuery query) {
        Objects.requireNonNull(query, "code excerpt query is required");
        requireVersion(query.projectIdentifier(), query.branch(), query.snapshotId(), query.commit());
        CodeSnippetResponse response = read(new CodeSnippetQuery(
                query.projectIdentifier(), query.branch(), query.path(), query.startLine(), query.lineCount()));
        if (!Objects.equals(response.snapshotId(), query.snapshotId())
                || !Objects.equals(response.commit(), query.commit())) {
            throw new CodeSnapshotVersionChangedException();
        }
        requireVersion(query.projectIdentifier(), query.branch(), query.snapshotId(), query.commit());
        return new CodeExcerpt(
                response.projectIdentifier(), response.branch(), response.snapshotId(), response.commit(),
                response.indexedAt(), response.path(), response.startLine(), response.endLine(),
                response.content(), response.truncated());
    }

    /**
     * @param projectIdentifier 项目标识
     * @param branch 可选分支
     * @return 普通 HTTP 状态视图
     */
    public ActiveCodeSnapshotView get(String projectIdentifier, String branch) {
        ResolvedCodeSnapshotScope scope = resolver.resolve(projectIdentifier, branch);
        return scope.active().map(active -> new ActiveCodeSnapshotView(
                scope.projectIdentifier(), scope.branch(), CodeSnapshotAvailability.INDEXED,
                active.snapshotId(), active.commit(), active.indexedAt(), active.indexedFileCount(),
                active.changeHint())).orElseGet(() -> new ActiveCodeSnapshotView(
                scope.projectIdentifier(), scope.branch(), CodeSnapshotAvailability.NOT_INDEXED,
                null, null, null, null, null));
    }

    /**
     * @param input 内部 HTTP 搜索输入
     * @return 固定活动快照的搜索响应
     */
    public CodeSearchResponse search(CodeSearchQuery input) {
        ValidatedSearch query = validate(input);
        ResolvedCodeSnapshotScope scope = resolver.resolve(input.projectIdentifier(), input.branch());
        ActiveCodeSnapshotDescriptor active = scope.active().orElseThrow(CodeSnapshotNotFoundException::new);
        List<CodeSearchResult> items = searchIndex
                .search(active, query.text(), query.target(), query.pathPrefix(), query.limit()).stream()
                .map(hit -> new CodeSearchResult(
                        scope.projectIdentifier(), scope.branch(), active.snapshotId(), active.commit(),
                        active.indexedAt(), hit.path(), hit.snippet(), hit.score(), hit.truncated()))
                .toList();
        return new CodeSearchResponse(items);
    }

    /**
     * @param query 内部 HTTP 片段输入
     * @return 固定活动快照的有界片段
     */
    public CodeSnippetResponse read(CodeSnippetQuery query) {
        ValidatedSnippet input = validate(query);
        ResolvedCodeSnapshotScope scope = resolver.resolve(query.projectIdentifier(), query.branch());
        ActiveCodeSnapshotDescriptor active = scope.active().orElseThrow(CodeSnapshotNotFoundException::new);
        String content = snippetIndex.read(active, input.path()).orElseThrow(CodeFileNotFoundException::new);
        List<String> lines = content.lines().toList();
        if (input.startLine() > lines.size()) {
            throw new CodeSnippetRangeInvalidException();
        }
        int endLine = Math.min(lines.size(), input.startLine() + input.lineCount() - 1);
        String snippet = String.join("\n", lines.subList(input.startLine() - 1, endLine));
        return new CodeSnippetResponse(
                scope.projectIdentifier(), scope.branch(), active.snapshotId(), active.commit(), active.indexedAt(),
                input.path(), input.startLine(), endLine, snippet, endLine < lines.size());
    }

    private void requireVersion(String project, String branch, Long snapshotId, String commit) {
        ActiveCodeState active = getActiveSnapshot(project, branch);
        if (active.status() != ActiveCodeState.Status.INDEXED
                || !Objects.equals(active.snapshotId(), snapshotId)
                || !Objects.equals(active.commit(), commit)) {
            throw new CodeSnapshotVersionChangedException();
        }
    }

    private boolean matchesVersion(CodeSearchResult item, Long snapshotId, String commit) {
        return Objects.equals(item.snapshotId(), snapshotId) && Objects.equals(item.commit(), commit);
    }

    private ActiveCodeState toApiSnapshot(ActiveCodeSnapshotView view) {
        return new ActiveCodeState(
                view.projectIdentifier(), view.branch(), ActiveCodeState.Status.valueOf(view.status().name()),
                view.snapshotId(), view.commit(), view.indexedAt(), view.indexedFileCount(),
                view.changeHint() == null ? null : ActiveCodeState.ChangeHint.valueOf(view.changeHint().name()));
    }

    private CodeMatches.Match toApiMatch(CodeSearchResult item) {
        return new CodeMatches.Match(
                item.projectIdentifier(), item.branch(), item.snapshotId(), item.commit(), item.indexedAt(),
                item.path(), item.snippet(), item.score(), item.truncated());
    }

    private ValidatedSearch validate(CodeSearchQuery query) {
        if (query == null || query.projectIdentifier() == null || query.projectIdentifier().isBlank()) {
            throw new IllegalArgumentException("code search project is required");
        }
        String text = query.query() == null ? "" : query.query().strip();
        if (text.isEmpty() || text.length() > 200) {
            throw new IllegalArgumentException("code search query length is invalid");
        }
        int limit = query.limit() == null ? 10 : query.limit();
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("code search limit is invalid");
        }
        String prefix = normalizePrefix(query.pathPrefix());
        return new ValidatedSearch(text,
                query.target() == null ? CodeSearchTarget.ALL : query.target(), prefix, limit);
    }

    private ValidatedSnippet validate(CodeSnippetQuery query) {
        if (query == null || query.projectIdentifier() == null || query.projectIdentifier().isBlank()) {
            throw new IllegalArgumentException("code snippet project is required");
        }
        String path = new RepositoryRelativePath(query.path()).value();
        int startLine = query.startLine() == null ? 1 : query.startLine();
        int lineCount = query.lineCount() == null ? 80 : query.lineCount();
        if (startLine < 1 || lineCount < 1 || lineCount > 200) {
            throw new IllegalArgumentException("code snippet line range is invalid");
        }
        return new ValidatedSnippet(path, startLine, lineCount);
    }

    private String normalizePrefix(String value) {
        if (value == null) {
            return null;
        }
        String prefix = value.strip();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return new RepositoryRelativePath(prefix).value();
    }

    private record ValidatedSearch(String text, CodeSearchTarget target, String pathPrefix, int limit) {
    }

    private record ValidatedSnippet(String path, int startLine, int lineCount) {
    }
}
