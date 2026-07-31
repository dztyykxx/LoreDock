package io.github.loredock.knowledge.model;

/**
 * 需要由应用层在同一事务中持久化的替代发布结果。
 *
 * @param publishedDocument 已发布且指向旧文档的新聚合
 * @param archivedDocument 已归档且指向新文档的旧聚合
 */
public record ReplacementPublicationPlan(
        KnowledgeDocument publishedDocument,
        KnowledgeDocument archivedDocument
) {
}
