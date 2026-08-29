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

/** 模型一次 Tool Call 的公开业务投影；同一调用从 STARTED 原位更新到终态。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_tool_invocation")
public class KnowledgeToolInvocationEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("conversation_id") private Long conversationId;
    @TableField("run_id") private Long runId;
    @TableField("tool_call_id") private String toolCallId;
    @TableField("sequence") private Integer sequence;
    @TableField("tool_name") private String toolName;
    @TableField("agent_node") private String agentNode;
    @TableField("purpose") private String purpose;
    @TableField("arguments_text") private String argumentsText;
    @TableField("result_text") private String resultText;
    @TableField("result_summary") private String resultSummary;
    @TableField("error_text") private String errorText;
    @TableField("status") private String status;
    @TableField("arguments_truncated") private Boolean argumentsTruncated;
    @TableField("result_truncated") private Boolean resultTruncated;
    @TableField("started_at") private Instant startedAt;
    @TableField("finished_at") private Instant finishedAt;
    @TableField("duration_millis") private Long durationMillis;
}
