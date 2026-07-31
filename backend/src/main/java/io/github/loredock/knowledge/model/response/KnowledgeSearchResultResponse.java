package io.github.loredock.knowledge.model.response;

import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMatchedBy;
import java.time.Instant;
import java.util.List;

/** 有限文档级搜索结果；同一文档的多个分块不得形成多条响应。 */
public record KnowledgeSearchResultResponse(
        Long documentId,
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
