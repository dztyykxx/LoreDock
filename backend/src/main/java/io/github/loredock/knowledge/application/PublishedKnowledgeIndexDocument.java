package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** T5 可读取的不可变活动投影文档；消费者仍须按实时文档资格复核候选 ID。 */
public record PublishedKnowledgeIndexDocument(
        UUID documentId,
        long sourceRevision,
        DocumentFormat format,
        String title,
        String body,
        String directory,
        List<DocumentTag> tags,
        DocumentSource source,
        KnowledgeScope scope,
        Instant sourceUpdatedAt
) {
}
