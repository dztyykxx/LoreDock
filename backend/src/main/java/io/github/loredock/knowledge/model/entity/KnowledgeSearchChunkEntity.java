package io.github.loredock.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * `knowledge_search_chunk` 的显式映射实体；TSVector、数组与向量写入由专用注解 SQL 参数化处理。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_search_chunk")
public class KnowledgeSearchChunkEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("generation_id")
    private Long generationId;

    @TableField("document_id")
    private Long documentId;

    @TableField("chunk_no")
    private Integer chunkNo;

    @TableField("start_offset")
    private Integer startOffset;

    @TableField("end_offset")
    private Integer endOffset;

    @TableField("content")
    private String content;

    @TableField("source_revision")
    private Long sourceRevision;

    @TableField("title")
    private String title;

    @TableField("tags")
    private String tags;

    @TableField("wiki_url")
    private String wikiUrl;

    @TableField("original_filename")
    private String originalFilename;

    @TableField("curation_note")
    private String curationNote;

    @TableField("title_terms")
    private String titleTerms;

    @TableField("tag_terms")
    private String tagTerms;

    @TableField("content_terms")
    private String contentTerms;

    @TableField("search_vector")
    private String searchVector;

    @TableField("embedding")
    private String embedding;

    @TableField("scope_type")
    private String scopeType;

    @TableField("project_id")
    private Long projectId;

    @TableField("branch_id")
    private Long branchId;

    @TableField("format")
    private String format;

    @TableField("source_type")
    private String sourceType;

    @TableField("normalized_tags")
    private String[] normalizedTags;

    @TableField("source_updated_at")
    private Instant sourceUpdatedAt;
}
