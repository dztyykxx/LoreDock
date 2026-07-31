package io.github.loredock.knowledge.model.response;

import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchWarning;
import java.util.List;

/** 单一活动 generation 的稳定、有界且可引用的知识搜索响应。 */
public record KnowledgeSearchHttpResponse(
        KnowledgeSearchContextResponse context,
        KnowledgeSearchMode mode,
        Long generationId,
        List<KnowledgeSearchWarning> warnings,
        List<KnowledgeSearchResultResponse> results
) {
    public KnowledgeSearchHttpResponse {
        warnings = List.copyOf(warnings);
        results = List.copyOf(results);
    }
}
