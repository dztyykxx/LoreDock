package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;

import java.util.UUID;

/** 导入条目持久化模型；成功项的文档关联必须与文档创建处于同一事务。 */
public record KnowledgeImportItemRecord(
        UUID id,
        UUID batchId,
        int ordinal,
        String entryName,
        ImportItemStatus status,
        ImportItemReason reason,
        String message,
        UUID documentId
) {
    /** 转换为不暴露内部标识的应用视图。 */
    public KnowledgeImportItemView toView() {
        return new KnowledgeImportItemView(ordinal, entryName, status, reason, message, documentId);
    }
}
