package io.github.loredock.code.application;

import io.github.loredock.code.domain.RepositoryRelativePath;
import org.springframework.stereotype.Service;

/** 固定活动范围后从 Lucene StoredField 计算有界行片段的应用用例。 */
@Service
public class CodeSnippetService implements CodeSnippetReadUseCase {

    private final ActiveCodeSnapshotResolver resolver;
    private final CodeIndexSnippetPort index;

    /**
     * @param resolver 已启用项目活动范围解析器
     * @param index 精确 StoredField 读取端口
     */
    public CodeSnippetService(ActiveCodeSnapshotResolver resolver, CodeIndexSnippetPort index) {
        this.resolver = resolver;
        this.index = index;
    }

    @Override
    public CodeSnippetResponse read(CodeSnippetQuery query) {
        ValidatedSnippet input = validate(query);
        ResolvedCodeSnapshotScope scope = resolver.resolve(query.projectIdentifier(), query.branch());
        ActiveCodeSnapshotDescriptor active = scope.active().orElseThrow(CodeSnapshotNotFoundException::new);
        String content = index.read(active, input.path()).orElseThrow(CodeFileNotFoundException::new);
        var lines = content.lines().toList();
        if (input.startLine() > lines.size()) {
            throw new CodeSnippetRangeInvalidException();
        }
        int endLine = Math.min(lines.size(), input.startLine() + input.lineCount() - 1);
        String snippet = String.join("\n", lines.subList(input.startLine() - 1, endLine));
        return new CodeSnippetResponse(
                scope.projectIdentifier(), scope.branch(), active.snapshotId(), active.commit(), active.indexedAt(),
                input.path(), input.startLine(), endLine, snippet, endLine < lines.size());
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

    private record ValidatedSnippet(String path, int startLine, int lineCount) {
    }
}
