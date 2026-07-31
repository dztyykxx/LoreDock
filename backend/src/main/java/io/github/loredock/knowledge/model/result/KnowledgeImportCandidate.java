package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.enums.DocumentFormat;

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
