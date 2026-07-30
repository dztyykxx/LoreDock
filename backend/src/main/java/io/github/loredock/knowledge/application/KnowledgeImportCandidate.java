package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTitle;

/**
 * 已完成字节、编码和文件名校验的导入候选；正文仍是纯文本，不代表已创建文档。
 */
public record KnowledgeImportCandidate(
        int ordinal,
        String entryName,
        DocumentFormat format,
        DocumentTitle title,
        DocumentBody body,
        DocumentDirectory directory,
        DocumentSource source
) {
}
