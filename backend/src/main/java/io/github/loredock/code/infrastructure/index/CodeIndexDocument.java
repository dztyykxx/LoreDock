package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.domain.CodeCommit;
import io.github.loredock.code.domain.RepositoryRelativePath;

import java.util.Objects;
import java.util.UUID;

/**
 * 单个允许代码文件的 Lucene 输入；构造时即拒绝缺失范围、非法 commit 和非规范路径。
 */
public record CodeIndexDocument(
        UUID projectId,
        UUID branchId,
        UUID snapshotId,
        UUID generationId,
        String commit,
        String path,
        String language,
        String content
) {
    /** 创建经过基础业务不变量验证的索引文档。 */
    public CodeIndexDocument {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(generationId, "generationId");
        commit = new CodeCommit(commit).value();
        path = new RepositoryRelativePath(path).value();
        language = Objects.requireNonNullElse(language, "text").strip().toLowerCase(java.util.Locale.ROOT);
        if (language.isEmpty()) {
            language = "text";
        }
        content = Objects.requireNonNull(content, "content");
    }

    /** @return 规范路径最后一个段，供文件名高权重检索。 */
    public String fileName() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** @return Java Unicode 行边界下的逻辑行数。 */
    public int lineCount() {
        return Math.toIntExact(content.lines().count());
    }
}
