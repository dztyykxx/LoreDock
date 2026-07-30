package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 知识检索分块 Mapper；复合主键和特殊 PostgreSQL 类型由数据库与专用注解 SQL 共同处理。 */
@Mapper
public interface KnowledgeSearchChunkMapper extends BaseMapper<KnowledgeSearchChunkEntity> {

    /**
     * 参数化写入分块并在 PostgreSQL 内构造带 A/B/C 权重的 tsvector；向量与标签均不拼接 SQL。
     */
    @Insert("""
            insert into knowledge_search_chunk(
                generation_id, document_id, chunk_no, start_offset, end_offset, content,
                title_terms, tag_terms, content_terms, search_vector, embedding,
                scope_type, project_id, branch_id, format, source_type, normalized_tags,
                source_updated_at)
            values (
                #{generationId}, #{documentId}, #{chunkNo}, #{startOffset}, #{endOffset}, #{content},
                #{titleTerms}, #{tagTerms}, #{contentTerms},
                setweight(to_tsvector('simple', #{titleTerms}), 'A') ||
                setweight(to_tsvector('simple', #{tagTerms}), 'B') ||
                setweight(to_tsvector('simple', #{contentTerms}), 'C'),
                cast(#{embedding} as vector),
                #{scopeType}, #{projectId}, #{branchId}, #{format}, #{sourceType},
                #{normalizedTags, jdbcType=ARRAY,
                    typeHandler=io.github.loredock.knowledge.infrastructure.persistence.PostgresTextArrayTypeHandler},
                #{sourceUpdatedAt})
            """)
    int insertSearchChunk(KnowledgeSearchChunkEntity entity);
}
