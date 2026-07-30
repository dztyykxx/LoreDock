package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentDirectory;

/** 普通知识目录与摘要分页查询。 */
public record BrowseKnowledgeDocumentsQuery(
        KnowledgeBrowseContext context,
        DocumentDirectory directory,
        int page,
        int size
) {
}
