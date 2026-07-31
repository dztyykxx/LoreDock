package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.DocumentRevision;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTag;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.ReplacementLink;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeIndexSyncStatus;
import java.time.Instant;
import java.util.List;

/** 完整文档应用视图；入口层根据权限决定是否映射管理审计字段。 */
public record KnowledgeDocumentView(
        Long id,
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
