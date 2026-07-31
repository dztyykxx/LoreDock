package io.github.loredock.knowledge.model.response;

import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeIndexSyncStatus;
import java.time.Instant;
import java.util.List;

/** 列表摘要响应，不包含正文、内部对象键或失败堆栈。 */
public record KnowledgeDocumentSummaryResponse(
        Long id,
        DocumentFormat format,
        String title,
        String directory,
        List<String> tags,
        DocumentSourceResponse source,
        KnowledgeScopeResponse scope,
        DocumentStatus status,
        long revision,
        KnowledgeIndexSyncStatus syncStatus,
        Instant updatedAt
) {
}
