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

/** 用户记忆变更后的完整快照；正文历史只用于管理员查看与审计。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("user_memory_revision")
public class UserMemoryRevisionEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("memory_id") private Long memoryId;
    @TableField("revision") private Long revision;
    @TableField("snapshot") private String snapshot;
    @TableField("operation") private String operation;
    @TableField("relation") private String relation;
    @TableField("reason") private String reason;
    @TableField("source_run_id") private Long sourceRunId;
    @TableField("source_conversation_id") private Long sourceConversationId;
    @TableField("source_message_id") private Long sourceMessageId;
    @TableField("operator_id") private String operatorId;
    @TableField("created_at") private Instant createdAt;
}
