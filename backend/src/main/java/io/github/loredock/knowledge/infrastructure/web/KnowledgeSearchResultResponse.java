package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.search.KnowledgeSearchMatchedBy;
import io.github.loredock.knowledge.domain.DocumentFormat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 有限文档级搜索结果；同一文档的多个分块不得形成多条响应。 */
public record KnowledgeSearchResultResponse(
        UUID documentId,
        KnowledgeSearchResultScopeResponse scope,
        String title,
        String snippet,
        boolean truncated,
        DocumentFormat format,
        List<String> tags,
        KnowledgeSearchSourceResponse source,
        Instant sourceUpdatedAt,
        double relevance,
        KnowledgeSearchMatchedBy matchedBy
) {
    public KnowledgeSearchResultResponse {
        tags = List.copyOf(tags);
    }
}
