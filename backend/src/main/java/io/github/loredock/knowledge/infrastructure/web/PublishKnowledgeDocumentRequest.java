package io.github.loredock.knowledge.infrastructure.web;

import java.util.UUID;

/**
 * 发布请求；替代目标为空表示普通发布，非空时必须原子归档同范围旧文档。
 *
 * @param replacesDocumentId 可选被替代文档 UUID
 */
public record PublishKnowledgeDocumentRequest(UUID replacesDocumentId) {
}
