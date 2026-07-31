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

/** `agent_run` 的显式映射实体，完整问题只以哈希和长度进入该实体。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("agent_run")
public class AgentRunEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("operator_id") private String operatorId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_hash") private String requestHash;
    @TableField("task_type") private String taskType;
    @TableField("question_hash") private String questionHash;
    @TableField("question_length") private Integer questionLength;
    @TableField("project_id") private Long projectId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("branch_id") private Long branchId;
    @TableField("branch_name") private String branchName;
    @TableField("snapshot_id") private Long snapshotId;
    @TableField("commit_hash") private String commitHash;
    @TableField("knowledge_generation_id") private Long knowledgeGenerationId;
    @TableField("agent_name") private String agentName;
    @TableField("model_name") private String modelName;
    @TableField("config_summary") private String configSummary;
    @TableField("status") private String status;
    @TableField("result_type") private String resultType;
    @TableField("answer_basis") private String answerBasis;
    @TableField("result_text") private String resultText;
    @TableField("refusal_reason") private String refusalReason;
    @TableField("error_code") private String errorCode;
    @TableField("event_sequence") private Long eventSequence;
    @TableField("step_count") private Integer stepCount;
    @TableField("model_call_count") private Integer modelCallCount;
    @TableField("retrieval_count") private Integer retrievalCount;
    @TableField("trimmed_character_count") private Integer trimmedCharacterCount;
    @TableField("input_tokens") private Long inputTokens;
    @TableField("output_tokens") private Long outputTokens;
    @TableField("elapsed_millis") private Long elapsedMillis;
    @TableField("accepted_at") private Instant acceptedAt;
    @TableField("started_at") private Instant startedAt;
    @TableField("finished_at") private Instant finishedAt;
    @TableField("updated_at") private Instant updatedAt;
}
