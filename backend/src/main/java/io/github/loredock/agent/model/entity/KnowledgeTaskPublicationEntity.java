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

/** 知识任务原子发布的幂等请求与已提交结果。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_task_publication")
public class KnowledgeTaskPublicationEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("conversation_id") private Long conversationId;
    @TableField("operator_id") private String operatorId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_hash") private String requestHash;
    @TableField("result_json") private String resultJson;
    @TableField("created_at") private Instant createdAt;
    @TableField("completed_at") private Instant completedAt;
}
