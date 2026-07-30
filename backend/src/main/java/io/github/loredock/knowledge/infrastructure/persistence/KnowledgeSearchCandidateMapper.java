package io.github.loredock.knowledge.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 知识关键词与精确向量候选 Mapper；所有客户端输入都通过参数绑定进入固定 SQL。 */
@Mapper
public interface KnowledgeSearchCandidateMapper {

    /**
     * 在 generation、业务范围和过滤条件内查询关键词候选。
     *
     * <p>合法短查询无法形成词项时才启用字面子串回退；该分支仍先应用强范围和固定上限，
     * 防止短词退化成跨项目或无界扫描。</p>
     */
    @Select("""
            <script>
            select c.document_id as "documentId", c.chunk_no as "chunkNo",
                   c.start_offset as "startOffset", c.end_offset as "endOffset", c.content,
                   d.title, d.tags::text as "tagsJson", d.source_type as "sourceType",
                   d.wiki_url as "wikiUrl", d.original_filename as "originalFilename",
                   d.curation_note as "curationNote", d.scope_type as "scopeType",
                   d.project_id as "projectId", d.branch_id as "branchId", d.format,
                   d.source_updated_at as "sourceUpdatedAt",
                   case when #{literalFallback} then
                       case
                           when position(lower(#{literalQuery}) in lower(d.title)) &gt; 0 then 3.0
                           when exists (
                               select 1 from unnest(c.normalized_tags) tag
                               where position(lower(#{literalQuery}) in lower(tag)) &gt; 0
                           ) then 2.0
                           else 1.0
                       end
                   else ts_rank_cd(c.search_vector, to_tsquery('simple', #{tsQuery}))
                   end as "rawScore"
            from knowledge_search_chunk c
            join knowledge_index_document d
              on d.generation_id = c.generation_id and d.document_id = c.document_id
            where c.generation_id = #{generationId}
              and (
                (#{contextType} = 'GLOBAL' and c.scope_type = 'GLOBAL')
                or (#{contextType} = 'PROJECT' and (
                    c.scope_type = 'GLOBAL'
                    or (c.scope_type = 'PROJECT' and c.project_id = #{projectId})
                    or (c.scope_type = 'BRANCH' and c.project_id = #{projectId}
                        and c.branch_id = #{branchId})
                ))
              )
              <if test="tags != null and tags.length > 0">
                and c.normalized_tags @&gt; #{tags, jdbcType=ARRAY,
                    typeHandler=io.github.loredock.knowledge.infrastructure.persistence.PostgresTextArrayTypeHandler}
              </if>
              <if test="format != null">and c.format = #{format}</if>
              <if test="sourceType != null">and c.source_type = #{sourceType}</if>
              and (
                (not #{literalFallback} and c.search_vector @@ to_tsquery('simple', #{tsQuery}))
                or (#{literalFallback} and (
                    position(lower(#{literalQuery}) in lower(d.title)) &gt; 0
                    or position(lower(#{literalQuery}) in lower(c.content)) &gt; 0
                    or exists (
                        select 1 from unnest(c.normalized_tags) tag
                        where position(lower(#{literalQuery}) in lower(tag)) &gt; 0
                    )
                ))
              )
            order by "rawScore" desc, c.document_id asc, c.chunk_no asc
            limit #{candidateLimit}
            </script>
            """)
    List<KnowledgeSearchCandidateRow> findKeywordCandidates(
            @Param("generationId") UUID generationId,
            @Param("contextType") String contextType,
            @Param("projectId") UUID projectId,
            @Param("branchId") UUID branchId,
            @Param("tags") String[] tags,
            @Param("format") String format,
            @Param("sourceType") String sourceType,
            @Param("tsQuery") String tsQuery,
            @Param("literalQuery") String literalQuery,
            @Param("literalFallback") boolean literalFallback,
            @Param("candidateLimit") int candidateLimit
    );

    /**
     * 在同一组前置范围与过滤条件内执行 pgvector 精确余弦查询；首版不使用近似索引。
     */
    @Select("""
            <script>
            select c.document_id as "documentId", c.chunk_no as "chunkNo",
                   c.start_offset as "startOffset", c.end_offset as "endOffset", c.content,
                   d.title, d.tags::text as "tagsJson", d.source_type as "sourceType",
                   d.wiki_url as "wikiUrl", d.original_filename as "originalFilename",
                   d.curation_note as "curationNote", d.scope_type as "scopeType",
                   d.project_id as "projectId", d.branch_id as "branchId", d.format,
                   d.source_updated_at as "sourceUpdatedAt",
                   1.0 - (c.embedding &lt;=&gt; cast(#{embedding} as vector)) as "rawScore"
            from knowledge_search_chunk c
            join knowledge_index_document d
              on d.generation_id = c.generation_id and d.document_id = c.document_id
            where c.generation_id = #{generationId}
              and (
                (#{contextType} = 'GLOBAL' and c.scope_type = 'GLOBAL')
                or (#{contextType} = 'PROJECT' and (
                    c.scope_type = 'GLOBAL'
                    or (c.scope_type = 'PROJECT' and c.project_id = #{projectId})
                    or (c.scope_type = 'BRANCH' and c.project_id = #{projectId}
                        and c.branch_id = #{branchId})
                ))
              )
              <if test="tags != null and tags.length > 0">
                and c.normalized_tags @&gt; #{tags, jdbcType=ARRAY,
                    typeHandler=io.github.loredock.knowledge.infrastructure.persistence.PostgresTextArrayTypeHandler}
              </if>
              <if test="format != null">and c.format = #{format}</if>
              <if test="sourceType != null">and c.source_type = #{sourceType}</if>
            order by c.embedding &lt;=&gt; cast(#{embedding} as vector), c.document_id asc, c.chunk_no asc
            limit #{candidateLimit}
            </script>
            """)
    List<KnowledgeSearchCandidateRow> findSemanticCandidates(
            @Param("generationId") UUID generationId,
            @Param("contextType") String contextType,
            @Param("projectId") UUID projectId,
            @Param("branchId") UUID branchId,
            @Param("tags") String[] tags,
            @Param("format") String format,
            @Param("sourceType") String sourceType,
            @Param("embedding") String embedding,
            @Param("candidateLimit") int candidateLimit
    );
}
