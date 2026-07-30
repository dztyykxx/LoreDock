package io.github.loredock.knowledge.application.search;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次请求固定使用的完整活动知识搜索 generation 元数据。
 *
 * @param generationId generation 标识
 * @param modelId Embedding 模型标识
 * @param modelChecksum 离线模型资源 SHA-256
 * @param vectorDimension 向量维度，当前版本固定 512
 * @param chunkStrategyVersion 分块策略版本
 * @param fusionConfigVersion 融合配置版本
 * @param documentCount 完整投影文档数
 * @param chunkCount 完整分块数
 * @param createdAt generation 创建时间
 */
public record ActiveKnowledgeSearchGeneration(
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
