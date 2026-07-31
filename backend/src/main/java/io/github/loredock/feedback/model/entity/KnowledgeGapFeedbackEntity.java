package io.github.loredock.feedback.model.entity;

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

/** 知识缺口固定范围、服务端问答摘要和人工处理状态的显式持久化实体。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_gap_feedback")
public class KnowledgeGapFeedbackEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("operator_id") private String operatorId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_hash") private String requestHash;
    @TableField("project_id") private Long projectId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("branch_id") private Long branchId;
    @TableField("branch_name") private String branchName;
    @TableField("question_id") private Long questionId;
    @TableField("run_id") private Long runId;
    @TableField("gap_type") private String gapType;
    @TableField("status") private String status;
    @TableField("question_text") private String questionText;
    @TableField("note") private String note;
    @TableField("result_type") private String resultType;
    @TableField("refusal_reason") private String refusalReason;
    @TableField("error_code") private String errorCode;
    @TableField("created_at") private Instant createdAt;
    @TableField("updated_at") private Instant updatedAt;
    @TableField("created_by") private String createdBy;
    @TableField("updated_by") private String updatedBy;
}
