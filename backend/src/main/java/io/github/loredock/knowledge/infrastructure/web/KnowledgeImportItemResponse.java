package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;

import java.util.UUID;

/** 安全的导入条目响应；entryName 与 message 均为不可信文本。 */
public record KnowledgeImportItemResponse(
        int ordinal,
        String entryName,
        ImportItemStatus status,
        ImportItemReason reason,
        String message,
        UUID documentId
) {
}
