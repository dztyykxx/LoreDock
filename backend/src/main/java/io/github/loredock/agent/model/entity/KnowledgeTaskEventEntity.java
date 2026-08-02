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

/** 面向知识任务 SSE 的持久化游标事件；大正文仍从聚合快照按 subjectId 读取。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_task_event")
public class KnowledgeTaskEventEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("conversation_id") private Long conversationId;
    @TableField("run_id") private Long runId;
    @TableField("event_type") private String eventType;
    @TableField("subject_id") private Long subjectId;
    @TableField("occurred_at") private Instant occurredAt;
}
