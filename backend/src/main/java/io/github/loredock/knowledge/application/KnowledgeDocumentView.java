package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentRevision;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeIndexSyncStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.ReplacementLink;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 完整文档应用视图；入口层根据权限决定是否映射管理审计字段。 */
public record KnowledgeDocumentView(
        UUID id,
        DocumentFormat format,
        String title,
        String body,
        String directory,
        List<DocumentTag> tags,
        DocumentSource source,
        KnowledgeScope scope,
        DocumentStatus status,
        DocumentRevision revision,
        Instant publishedAt,
        String publishedBy,
        Instant archivedAt,
        String archivedBy,
        ReplacementLink replacement,
        KnowledgeIndexSyncStatus syncStatus,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
