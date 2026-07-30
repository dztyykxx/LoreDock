package io.github.loredock.knowledge.application.search.indexing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 搜索 generation 元数据与分块的持久化端口，不向应用层暴露 PostgreSQL 特殊类型。 */
public interface KnowledgeSearchIndexRepository {

    /**
     * @param metadata 完整 generation 配置与计数
     * @throws RuntimeException generation 不存在、配置不合法或已重复写入时失败
     */
    void createGeneration(KnowledgeSearchGenerationMetadata metadata);

    /**
     * 幂等写入一个有界分块批次；相同复合键重试更新为同一事实，任一向量维度或数值非法时，
     * 整个批次必须在执行 SQL 前失败。
     *
     * @param chunks 同一构建流程产生的分块批次
     * @throws IllegalArgumentException 向量不是 512 维或包含 NaN/Infinity
     */
    void writeChunks(List<KnowledgeSearchChunkWrite> chunks);

    /**
     * @param generationId generation 标识
     * @return generation 的固定搜索元数据；旧投影 generation 返回空
     */
    Optional<KnowledgeSearchGenerationMetadata> findGeneration(UUID generationId);
}
