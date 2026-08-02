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

/** 可进入知识任务对话时间线的公开消息；不保存完整 Tool 返回或模型思维链。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_task_message")
public class KnowledgeTaskMessageEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("conversation_id") private Long conversationId;
    @TableField("run_id") private Long runId;
    @TableField("role") private String role;
    @TableField("subject_name") private String subjectName;
    @TableField("content") private String content;
    @TableField("created_at") private Instant createdAt;
}
