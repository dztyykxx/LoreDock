package io.github.loredock.knowledge.application.search;

/**
 * 已校验的离线 Embedding 模型描述。
 *
 * @param modelId 模型稳定标识
 * @param checksum 模型资源 SHA-256
 * @param dimension 输出向量维度
 */
public record KnowledgeEmbeddingModelDescriptor(String modelId, String checksum, int dimension) {
}
