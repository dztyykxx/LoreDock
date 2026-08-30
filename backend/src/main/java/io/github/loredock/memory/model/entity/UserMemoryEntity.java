package io.github.loredock.memory.model.entity;

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

/** 用户长期偏好记忆的显式持久化实体（与 user_memory 表一一对应）。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("user_memory")
public class UserMemoryEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("scope_type") private String scopeType;
    @TableField("project_id") private Long projectId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("category") private String category;
    @TableField("title") private String title;
    @TableField("summary") private String summary;
    @TableField("content") private String content;
    @TableField("source_type") private String sourceType;
    @TableField("source_run_id") private Long sourceRunId;
    @TableField("source_conversation_id") private Long sourceConversationId;
    @TableField("status") private String status;
    @TableField("use_count") private Long useCount;
    @TableField("last_used_at") private Instant lastUsedAt;
    @TableField("created_at") private Instant createdAt;
    @TableField("updated_at") private Instant updatedAt;
    @TableField("created_by") private String createdBy;
    @TableField("updated_by") private String updatedBy;
}
