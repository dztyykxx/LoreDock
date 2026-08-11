package io.github.loredock.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.knowledge.model.entity.KnowledgeSearchChunkEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** 知识检索分块 Mapper；复合主键和特殊 PostgreSQL 类型由数据库与专用注解 SQL 共同处理。 */
@Mapper
public interface KnowledgeSearchChunkMapper extends BaseMapper<KnowledgeSearchChunkEntity> {

    /**
     * 参数化写入分块并在 PostgreSQL 内构造带 A/B/C 权重的 tsvector；向量与标签均不拼接 SQL。
     */
    @Insert("""
            insert into knowledge_search_chunk(
                generation_id, document_id, chunk_no, start_offset, end_offset, content,
                source_revision, title, tags, wiki_url, original_filename, curation_note,
                title_terms, tag_terms, content_terms, search_vector, embedding,
                scope_type, project_id, branch_id, format, source_type, normalized_tags,
                source_updated_at)
            values (
                #{generationId}, #{documentId}, #{chunkNo}, #{startOffset}, #{endOffset}, #{content},
                #{sourceRevision}, #{title}, cast(#{tags} as jsonb), #{wikiUrl},
                #{originalFilename}, #{curationNote},
                #{titleTerms}, #{tagTerms}, #{contentTerms},
                setweight(to_tsvector('simple', #{titleTerms}), 'A') ||
                setweight(to_tsvector('simple', #{tagTerms}), 'B') ||
                setweight(to_tsvector('simple', #{contentTerms}), 'C'),
                cast(#{embedding} as vector),
                #{scopeType}, #{projectId}, #{branchId}, #{format}, #{sourceType},
                #{normalizedTags, jdbcType=ARRAY,
                    typeHandler=io.github.loredock.knowledge.config.PostgresTextArrayTypeHandler},
                #{sourceUpdatedAt})
            on conflict (generation_id, document_id, chunk_no) do update set
                start_offset = excluded.start_offset,
                end_offset = excluded.end_offset,
                content = excluded.content,
                source_revision = excluded.source_revision,
                title = excluded.title,
                tags = excluded.tags,
                wiki_url = excluded.wiki_url,
                original_filename = excluded.original_filename,
                curation_note = excluded.curation_note,
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
    long countByGeneration(@Param("generationId") Long generationId);

    /** @return 指定 generation 实际覆盖的文档数。 */
    @Select("select count(distinct document_id) from knowledge_search_chunk where generation_id = #{generationId}")
    long countDocumentsByGeneration(@Param("generationId") Long generationId);

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
    long countInvalidDocuments(@Param("generationId") Long generationId);

    /** @return 活动 generation 中指定文档的唯一来源修订。 */
    @Results(id = "activeRevisionResult", value = {
            @Result(column = "document_id", property = "documentId"),
            @Result(column = "source_revision", property = "sourceRevision")
    })
    @Select("""
            <script>
            select chunk.document_id, max(chunk.source_revision) as source_revision
            from knowledge_search_chunk chunk
            join knowledge_index_generation generation on generation.id = chunk.generation_id
            where generation.status = 'ACTIVE' and chunk.document_id in
            <foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>
                #{documentId}
            </foreach>
            group by chunk.document_id
            </script>
            """)
    java.util.List<KnowledgeSearchChunkEntity> selectActiveRevisions(
            @Param("documentIds") java.util.Collection<Long> documentIds);

    /**
     * @param generationId 目标 generation
     * @return 需要增量刷新索引的文档：当前 PUBLISHED 但该 generation 中没有同修订分块的文档
     */
    @Select("""
            select distinct d.id
            from knowledge_document d
            where d.status = 'PUBLISHED'
              and not exists (
                  select 1
                  from knowledge_search_chunk c
                  where c.generation_id = #{generationId}
                    and c.document_id = d.id
                    and c.source_revision = d.revision)
            order by d.id
            """)
    List<Long> selectDocumentIdsNeedingRefresh(@Param("generationId") Long generationId);

    /**
     * @param generationId 目标 generation
     * @return 需要从索引移除的文档：该 generation 有分块但当前已不是 PUBLISHED 的文档
     */
    @Select("""
            select distinct c.document_id
            from knowledge_search_chunk c
            where c.generation_id = #{generationId}
              and not exists (
                  select 1
                  from knowledge_document d
                  where d.id = c.document_id
                    and d.status = 'PUBLISHED')
            order by c.document_id
            """)
    List<Long> selectDocumentIdsToRemove(@Param("generationId") Long generationId);

    /** @param generationId 目标 generation @param documentIds 文档标识集合 @return 实际删除的分块数 */
    @Delete("""
            <script>
            delete from knowledge_search_chunk
            where generation_id = #{generationId}
              and document_id in
              <foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>
                  #{documentId}
              </foreach>
            </script>
            """)
    int deleteChunksByGenerationAndDocuments(
            @Param("generationId") Long generationId,
            @Param("documentIds") List<Long> documentIds);

    /**
     * @param generationId 目标 generation
     * @param documentIds 本次刷新涉及的文档标识集合
     * @return 其中分块序号不连续、空关键词向量、维度错误或 offset 非法的文档数量
     */
    @Select("""
            <script>
            select count(*) from (
                select document_id
                from knowledge_search_chunk
                where generation_id = #{generationId}
                  and document_id in
                  <foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>
                      #{documentId}
                  </foreach>
                group by document_id
                having min(chunk_no) &lt;&gt; 0
                    or max(chunk_no) + 1 &lt;&gt; count(*)
                    or bool_or(search_vector = ''::tsvector)
                    or bool_or(vector_dims(embedding) &lt;&gt; 512)
                    or bool_or(start_offset &lt; 0 or end_offset &lt;= start_offset)
            ) invalid_document
            </script>
            """)
    long countInvalidDocumentsByGenerationAndDocuments(
            @Param("generationId") Long generationId,
            @Param("documentIds") List<Long> documentIds);
}
