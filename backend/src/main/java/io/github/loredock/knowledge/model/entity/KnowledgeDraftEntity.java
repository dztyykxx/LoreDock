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

/** 版本化知识草稿的范围、基线、当前修订和发布指针。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_draft")
public class KnowledgeDraftEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("conversation_id") private Long conversationId;
    @TableField("operator_id") private String operatorId;
    @TableField("project_id") private Long projectId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("title") private String title;
    @TableField("operation") private String operation;
    @TableField("baseline_document_id") private Long baselineDocumentId;
    @TableField("baseline_revision") private Long baselineRevision;
    @TableField("directory_path") private String directoryPath;
    @TableField("current_revision") private Long currentRevision;
    @TableField("create_run_id") private Long createRunId;
    @TableField("create_idempotency_key") private String createIdempotencyKey;
    @TableField("create_request_hash") private String createRequestHash;
    @TableField("published_document_id") private Long publishedDocumentId;
    @TableField("published_revision") private Long publishedRevision;
    @TableField("created_at") private Instant createdAt;
    @TableField("updated_at") private Instant updatedAt;
}
