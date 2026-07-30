package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.search.KnowledgeSearchMode;
import io.github.loredock.knowledge.application.search.KnowledgeSearchWarning;

import java.util.List;
import java.util.UUID;

/** 单一活动 generation 的稳定、有界且可引用的知识搜索响应。 */
public record KnowledgeSearchHttpResponse(
        KnowledgeSearchContextResponse context,
        KnowledgeSearchMode mode,
        UUID generationId,
        List<KnowledgeSearchWarning> warnings,
        List<KnowledgeSearchResultResponse> results
) {
    public KnowledgeSearchHttpResponse {
        warnings = List.copyOf(warnings);
        results = List.copyOf(results);
    }
}
