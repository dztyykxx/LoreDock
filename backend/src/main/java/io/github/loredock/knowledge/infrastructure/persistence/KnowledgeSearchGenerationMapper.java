package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 检索 generation 元数据 Mapper；一对一与计数约束由 V5 数据库结构保护。 */
@Mapper
public interface KnowledgeSearchGenerationMapper extends BaseMapper<KnowledgeSearchGenerationEntity> {

    /**
     * @return 同时处于 ACTIVE 且拥有完整 V5 检索元数据的 generation；旧 ACTIVE 没有元数据时为空
     */
    @Select("""
            select search.generation_id as "generationId", search.model_id as "modelId",
                   search.model_checksum as "modelChecksum", search.vector_dimension as "vectorDimension",
                   search.chunk_strategy_version as "chunkStrategyVersion",
                   search.fusion_config_version as "fusionConfigVersion",
                   search.document_count as "documentCount", search.chunk_count as "chunkCount",
                   search.created_at as "createdAt"
            from knowledge_search_generation search
            join knowledge_index_generation generation on generation.id = search.generation_id
            where generation.status = 'ACTIVE'
            """)
    KnowledgeSearchGenerationEntity selectActiveComplete();
}
