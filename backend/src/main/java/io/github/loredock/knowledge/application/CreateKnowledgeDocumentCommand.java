package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeScope;

/** 管理员创建草稿的完整应用输入；范围进入用例前必须已解析为稳定 UUID。 */
public record CreateKnowledgeDocumentCommand(
        DocumentFormat format,
        DocumentTitle title,
        DocumentBody body,
        DocumentDirectory directory,
        DocumentTags tags,
        DocumentSource source,
        KnowledgeScope scope
) {
}
