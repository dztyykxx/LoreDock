package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.KnowledgeIndexSyncStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理详情响应，包含生命周期审计、替代追溯与派生索引同步状态。 */
public record AdminKnowledgeDocumentResponse(
        UUID id,
        DocumentFormat format,
        String title,
        String body,
        String directory,
        List<String> tags,
        DocumentSourceResponse source,
        KnowledgeScopeResponse scope,
        DocumentStatus status,
        long revision,
        Instant publishedAt,
        String publishedBy,
        Instant archivedAt,
        String archivedBy,
        ReplacementResponse replacement,
        KnowledgeIndexSyncStatus syncStatus,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
