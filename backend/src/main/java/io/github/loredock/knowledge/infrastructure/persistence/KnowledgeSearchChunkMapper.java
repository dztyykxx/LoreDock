package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

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
            on conflict (generation_id, document_id, chunk_no) do update set
                start_offset = excluded.start_offset,
                end_offset = excluded.end_offset,
                content = excluded.content,
                title_terms = excluded.title_terms,
                tag_terms = excluded.tag_terms,
                content_terms = excluded.content_terms,
                search_vector = excluded.search_vector,
                embedding = excluded.embedding,
                scope_type = excluded.scope_type,
                project_id = excluded.project_id,
                branch_id = excluded.branch_id,
                format = excluded.format,
                source_type = excluded.source_type,
                normalized_tags = excluded.normalized_tags,
                source_updated_at = excluded.source_updated_at
            """)
    int insertSearchChunk(KnowledgeSearchChunkEntity entity);

    /** @return 指定 generation 的实际分块数。 */
    @Select("select count(*) from knowledge_search_chunk where generation_id = #{generationId}")
    long countByGeneration(@Param("generationId") UUID generationId);

    /** @return 指定 generation 实际覆盖的文档数。 */
    @Select("select count(distinct document_id) from knowledge_search_chunk where generation_id = #{generationId}")
    long countDocumentsByGeneration(@Param("generationId") UUID generationId);

    /**
     * @return 分块序号不连续、空关键词向量、维度错误或 offset 非法的文档数量
     */
    @Select("""
            select count(*) from (
                select document_id
                from knowledge_search_chunk
                where generation_id = #{generationId}
                group by document_id
                having min(chunk_no) <> 0
                    or max(chunk_no) + 1 <> count(*)
                    or bool_or(search_vector = ''::tsvector)
                    or bool_or(vector_dims(embedding) <> 512)
                    or bool_or(start_offset < 0 or end_offset <= start_offset)
            ) invalid_document
            """)
    long countInvalidDocuments(@Param("generationId") UUID generationId);
}
