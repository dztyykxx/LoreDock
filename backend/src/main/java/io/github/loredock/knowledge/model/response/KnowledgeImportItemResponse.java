package io.github.loredock.knowledge.model.response;

import io.github.loredock.knowledge.model.enums.ImportItemReason;
import io.github.loredock.knowledge.model.enums.ImportItemStatus;

/** 安全的导入条目响应；entryName 与 message 均为不可信文本。 */
public record KnowledgeImportItemResponse(
        int ordinal,
        String entryName,
        ImportItemStatus status,
        ImportItemReason reason,
        String message,
        Long documentId
) {
}
