package io.github.loredock.knowledge.model.result;

import java.util.Map;

/** 活动 generation 是否存在及指定文档在其中的来源修订。 */
public record ActiveKnowledgeIndexRevisions(
        boolean activeGenerationExists,
        Map<Long, Long> sourceRevisions
) {
    public ActiveKnowledgeIndexRevisions {
        sourceRevisions = Map.copyOf(sourceRevisions);
    }
}
