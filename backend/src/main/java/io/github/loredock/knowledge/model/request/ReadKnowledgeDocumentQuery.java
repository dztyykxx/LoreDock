package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;

/** 普通详情读取必须同时携带入口上下文，避免先按 ID 跨范围加载再隐藏。 */
public record ReadKnowledgeDocumentQuery(KnowledgeBrowseContext context, Long documentId) {
}
