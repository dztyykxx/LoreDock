package io.github.loredock.knowledge.model.snapshot;

import io.github.loredock.knowledge.model.enums.ImportItemReason;
import io.github.loredock.knowledge.model.enums.ImportItemStatus;
import io.github.loredock.knowledge.model.result.KnowledgeImportItemView;

/** 导入条目持久化模型；成功项的文档关联必须与文档创建处于同一事务。 */
public record KnowledgeImportItemRecord(
        Long id,
        Long batchId,
        int ordinal,
        String entryName,
        ImportItemStatus status,
        ImportItemReason reason,
        String message,
        Long documentId
) {
    /** 转换为不暴露内部标识的应用视图。 */
    public KnowledgeImportItemView toView() {
        return new KnowledgeImportItemView(ordinal, entryName, status, reason, message, documentId);
    }
}
