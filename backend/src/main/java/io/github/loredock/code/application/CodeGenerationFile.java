package io.github.loredock.code.application;

import io.github.loredock.code.domain.RepositoryRelativePath;

import java.util.Locale;
import java.util.Objects;

/** 允许进入候选 generation 的完整 UTF-8 文本，不携带任何服务器物理路径。 */
public record CodeGenerationFile(String path, String language, String content) {

    /** 创建已规范化的逻辑代码文件。 */
    public CodeGenerationFile {
        path = new RepositoryRelativePath(path).value();
        language = Objects.requireNonNullElse(language, "text").strip().toLowerCase(Locale.ROOT);
        if (language.isEmpty()) {
            language = "text";
        }
        content = Objects.requireNonNull(content, "content");
    }
}
