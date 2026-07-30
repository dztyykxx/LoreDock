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
 * `knowledge_document` 的 MyBatis-Plus 显式映射实体；领域不变量由领域聚合和数据库约束共同保护。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    @TableField("format")
    private String format;

    @TableField("title")
    private String title;

    @TableField("body")
    private String body;

    @TableField("directory_path")
    private String directoryPath;

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

    @TableField("status")
    private String status;

    @TableField("revision")
    private Long revision;

    @TableField("replaces_document_id")
    private UUID replacesDocumentId;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("published_by")
    private String publishedBy;

    @TableField("archived_at")
    private Instant archivedAt;

    @TableField("archived_by")
    private String archivedBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
