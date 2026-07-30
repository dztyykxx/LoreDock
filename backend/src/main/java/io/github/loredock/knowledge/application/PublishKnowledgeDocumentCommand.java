package io.github.loredock.knowledge.application;

import java.util.UUID;

/**
 * 发布文档输入；可选替代目标必须在同一事务内校验、建立追溯并归档。
 *
 * @param documentId 待发布文档
 * @param replacesDocumentId 可选的被替代已发布文档
 */
public record PublishKnowledgeDocumentCommand(UUID documentId, UUID replacesDocumentId) {
}
