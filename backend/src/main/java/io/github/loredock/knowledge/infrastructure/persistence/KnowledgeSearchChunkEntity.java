package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

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

    @TableId(value = "generation_id", type = IdType.INPUT)
    private UUID generationId;

    @TableField("document_id")
    private UUID documentId;

    @TableField("chunk_no")
    private Integer chunkNo;

    @TableField("start_offset")
    private Integer startOffset;

    @TableField("end_offset")
    private Integer endOffset;

    @TableField("content")
    private String content;

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
    private UUID projectId;

    @TableField("branch_id")
    private UUID branchId;

    @TableField("format")
    private String format;

    @TableField("source_type")
    private String sourceType;

    @TableField("normalized_tags")
    private String[] normalizedTags;

    @TableField("source_updated_at")
    private Instant sourceUpdatedAt;
}
