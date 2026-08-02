package io.github.loredock.agent.model.entity;

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

/** 知识任务会话的项目归属、触发事实与当前草稿指针。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_task_conversation")
public class KnowledgeTaskConversationEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("operator_id") private String operatorId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_hash") private String requestHash;
    @TableField("project_id") private Long projectId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("trigger_type") private String triggerType;
    @TableField("trigger_reason") private String triggerReason;
    @TableField("target_skill") private String targetSkill;
    @TableField("goal") private String goal;
    @TableField("current_draft_id") private Long currentDraftId;
    @TableField("created_at") private Instant createdAt;
    @TableField("updated_at") private Instant updatedAt;
}
