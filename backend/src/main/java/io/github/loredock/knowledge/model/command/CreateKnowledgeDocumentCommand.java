package io.github.loredock.knowledge.model.command;

import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.DocumentFormat;

/** 管理员创建草稿的完整应用输入；范围进入用例前必须已解析为稳定 Long。 */
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
