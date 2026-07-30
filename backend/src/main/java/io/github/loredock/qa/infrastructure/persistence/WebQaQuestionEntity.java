package io.github.loredock.qa.infrastructure.persistence;

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

/** Web 问答身份、幂等键和运行固定范围的显式持久化实体。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("web_qa_question")
public class WebQaQuestionEntity {
    @TableId(value = "id", type = IdType.INPUT) private UUID id;
    @TableField("operator_id") private String operatorId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_hash") private String requestHash;
    @TableField("project_id") private UUID projectId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("branch_id") private UUID branchId;
    @TableField("branch_name") private String branchName;
    @TableField("run_id") private UUID runId;
    @TableField("created_at") private Instant createdAt;
}
