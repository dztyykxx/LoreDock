package io.github.loredock.knowledge.application;

import java.util.List;
import java.util.UUID;

/** 活动 generation 的一批稳定投影。 */
public record PublishedKnowledgeIndexBatch(
        UUID generationId,
        List<PublishedKnowledgeIndexDocument> documents,
        UUID nextAfterDocumentId,
        boolean hasMore
) {
}
