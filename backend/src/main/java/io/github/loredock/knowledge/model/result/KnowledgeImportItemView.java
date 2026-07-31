package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.enums.ImportItemReason;
import io.github.loredock.knowledge.model.enums.ImportItemStatus;

/** 稳定排序的导入条目结果，不包含正文、对象键或解析器内部错误。 */
public record KnowledgeImportItemView(
        int ordinal,
        String entryName,
        ImportItemStatus status,
        ImportItemReason reason,
        String message,
        Long documentId
) {
}
