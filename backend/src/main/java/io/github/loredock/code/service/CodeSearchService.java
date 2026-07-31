package io.github.loredock.code.service;

import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.model.RepositoryRelativePath;
import io.github.loredock.code.model.enums.CodeSearchTarget;
import io.github.loredock.code.model.request.CodeSearchQuery;
import io.github.loredock.code.model.response.CodeSearchResponse;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.CodeSearchResult;
import io.github.loredock.code.model.result.ResolvedCodeSnapshotScope;
import io.github.loredock.code.service.index.LuceneCodeIndexSearcher;
import org.springframework.stereotype.Service;

/** 固定一次活动范围、验证有界输入并映射带来源代码命中的搜索用例。 */
@Service
public class CodeSearchService {

    private final ActiveCodeSnapshotResolver resolver;
    private final LuceneCodeIndexSearcher index;

    /**
     * @param resolver 已启用项目活动范围解析器
     * @param index 固定 generation Lucene 查询端口
     */
    public CodeSearchService(ActiveCodeSnapshotResolver resolver, LuceneCodeIndexSearcher index) {
        this.resolver = resolver;
        this.index = index;
    }

    public CodeSearchResponse search(CodeSearchQuery input) {
        ValidatedSearch query = validate(input);
        ResolvedCodeSnapshotScope scope = resolver.resolve(input.projectIdentifier(), input.branch());
        ActiveCodeSnapshotDescriptor active = scope.active().orElseThrow(CodeSnapshotNotFoundException::new);
        var items = index.search(active, query.text(), query.target(), query.pathPrefix(), query.limit()).stream()
                .map(hit -> new CodeSearchResult(
                        scope.projectIdentifier(), scope.branch(), active.snapshotId(), active.commit(),
                        active.indexedAt(), hit.path(), hit.snippet(), hit.score(), hit.truncated()))
                .toList();
        return new CodeSearchResponse(items);
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
        return new ValidatedSearch(text, query.target() == null ? CodeSearchTarget.ALL : query.target(), prefix, limit);
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
}
