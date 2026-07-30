package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 成功原子激活的新 generation 摘要。 */
public record KnowledgeIndexRebuildResult(UUID generationId, long documentCount) {
}
