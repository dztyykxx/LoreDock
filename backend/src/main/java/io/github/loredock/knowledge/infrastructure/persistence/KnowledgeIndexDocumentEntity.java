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
 * `knowledge_index_document` 的只读投影映射实体；复合主键由数据库保护，generation 标识作为输入主键。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_index_document")
public class KnowledgeIndexDocumentEntity {

    @TableId(value = "generation_id", type = IdType.INPUT)
    private UUID generationId;

    @TableField("document_id")
    private UUID documentId;

    @TableField("source_revision")
    private Long sourceRevision;

    @TableField("format")
    private String format;

    @TableField("title")
    private String title;

    @TableField("body")
    private String body;

    @TableField("directory_path")
    private String directoryPath;

    @TableField("tags")
    private String tags;

    @TableField("scope_type")
    private String scopeType;

    @TableField("project_id")
    private UUID projectId;

    @TableField("branch_id")
    private UUID branchId;

    @TableField("source_type")
    private String sourceType;

    @TableField("wiki_url")
    private String wikiUrl;

    @TableField("original_filename")
    private String originalFilename;

    @TableField("curation_note")
    private String curationNote;

    @TableField("source_updated_at")
    private Instant sourceUpdatedAt;
}
