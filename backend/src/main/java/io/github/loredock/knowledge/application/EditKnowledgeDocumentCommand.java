package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeScope;

import java.util.UUID;

/** 管理员全量编辑文档的应用输入；同值请求必须保持修订号和审计时间不变。 */
public record EditKnowledgeDocumentCommand(
        UUID documentId,
        DocumentFormat format,
        DocumentTitle title,
        DocumentBody body,
        DocumentDirectory directory,
        DocumentTags tags,
        DocumentSource source,
        KnowledgeScope scope
) {
}
