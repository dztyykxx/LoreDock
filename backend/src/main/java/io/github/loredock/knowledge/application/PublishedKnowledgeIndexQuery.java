package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 活动 generation 的主键游标批量读取输入；上下文必须明确项目与分支范围。 */
public record PublishedKnowledgeIndexQuery(
        KnowledgeBrowseContext context,
        UUID afterDocumentId,
        int size
) {
}
