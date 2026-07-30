package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.KnowledgeIndexSyncStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 列表摘要响应，不包含正文、内部对象键或失败堆栈。 */
public record KnowledgeDocumentSummaryResponse(
        UUID id,
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
