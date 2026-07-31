package io.github.loredock.knowledge.model.command;

import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.DocumentFormat;

/** 管理员全量编辑文档的应用输入；同值请求必须保持修订号和审计时间不变。 */
public record EditKnowledgeDocumentCommand(
        Long documentId,
        DocumentFormat format,
        DocumentTitle title,
        DocumentBody body,
        DocumentDirectory directory,
        DocumentTags tags,
        DocumentSource source,
        KnowledgeScope scope
) {
}
