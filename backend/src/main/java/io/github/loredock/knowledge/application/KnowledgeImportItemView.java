package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;

import java.util.UUID;

/** 稳定排序的导入条目结果，不包含正文、对象键或解析器内部错误。 */
public record KnowledgeImportItemView(
        int ordinal,
        String entryName,
        ImportItemStatus status,
        ImportItemReason reason,
        String message,
        UUID documentId
) {
}
