package io.github.loredock.knowledge.application;

import java.util.UUID;

/**
 * 归档文档输入；操作者与时间由受信任的审计端口提供，不能接受客户端伪造。
 *
 * @param documentId 待归档文档 UUID
 */
public record ArchiveKnowledgeDocumentCommand(UUID documentId) {
}
