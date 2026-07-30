package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeIndexSyncStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 普通与管理列表共用的文档摘要，不包含正文或内部对象键。 */
public record KnowledgeDocumentSummary(
        UUID id,
        DocumentFormat format,
        String title,
        String directory,
        List<DocumentTag> tags,
        DocumentSource source,
        KnowledgeScope scope,
        DocumentStatus status,
        long revision,
        KnowledgeIndexSyncStatus syncStatus,
        Instant updatedAt
) {
}
