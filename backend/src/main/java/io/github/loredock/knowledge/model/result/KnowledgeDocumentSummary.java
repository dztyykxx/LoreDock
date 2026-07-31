package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTag;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeIndexSyncStatus;
import java.time.Instant;
import java.util.List;

/** 普通与管理列表共用的文档摘要，不包含正文或内部对象键。 */
public record KnowledgeDocumentSummary(
        Long id,
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
