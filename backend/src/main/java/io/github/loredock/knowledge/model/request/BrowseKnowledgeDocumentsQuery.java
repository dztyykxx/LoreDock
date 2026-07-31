package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;

/** 普通知识目录与摘要分页查询。 */
public record BrowseKnowledgeDocumentsQuery(
        KnowledgeBrowseContext context,
        DocumentDirectory directory,
        int page,
        int size
) {
}
