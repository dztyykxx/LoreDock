package io.github.loredock.knowledge.model.command;


/**
 * 归档文档输入；操作者与时间由受信任的审计端口提供，不能接受客户端伪造。
 *
 * @param documentId 待归档文档 Long
 */
public record ArchiveKnowledgeDocumentCommand(Long documentId) {
}
