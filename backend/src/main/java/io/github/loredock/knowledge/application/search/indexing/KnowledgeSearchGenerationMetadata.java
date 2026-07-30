package io.github.loredock.knowledge.application.search.indexing;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次知识搜索 generation 的固定模型、分块、融合配置与完整性计数。
 *
 * @param generationId 对应知识投影 generation
 * @param modelId 离线 Embedding 模型标识
 * @param modelChecksum 模型文件 SHA-256
 * @param vectorDimension 向量维度，当前版本固定为 512
 * @param chunkStrategyVersion 分块策略版本
 * @param fusionConfigVersion 融合配置版本
 * @param documentCount 投影文档数
 * @param chunkCount 检索分块数
 * @param createdAt 元数据创建时间
 */
public record KnowledgeSearchGenerationMetadata(
        UUID generationId,
        String modelId,
        String modelChecksum,
        int vectorDimension,
        String chunkStrategyVersion,
        String fusionConfigVersion,
        long documentCount,
        long chunkCount,
        Instant createdAt
) {
}
