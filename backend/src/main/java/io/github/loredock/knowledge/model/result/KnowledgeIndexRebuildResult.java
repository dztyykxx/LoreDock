package io.github.loredock.knowledge.model.result;


/** 成功原子激活的新 generation 摘要。 */
public record KnowledgeIndexRebuildResult(Long generationId, long documentCount) {
}
