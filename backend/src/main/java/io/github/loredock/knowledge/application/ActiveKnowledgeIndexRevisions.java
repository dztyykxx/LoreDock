package io.github.loredock.knowledge.application;

import java.util.Map;
import java.util.UUID;

/** 活动 generation 是否存在及指定文档在其中的来源修订。 */
public record ActiveKnowledgeIndexRevisions(
        boolean activeGenerationExists,
        Map<UUID, Long> sourceRevisions
) {
    public ActiveKnowledgeIndexRevisions {
        sourceRevisions = Map.copyOf(sourceRevisions);
    }
}
