package io.github.loredock.knowledgegap.infrastructure.persistence;

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

/** 知识缺口与同一 Agent 运行证据的有序关联显式持久化实体。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_gap_feedback_citation")
public class KnowledgeGapFeedbackCitationEntity {
    @TableId(value = "id", type = IdType.INPUT) private UUID id;
    @TableField("feedback_id") private UUID feedbackId;
    @TableField("run_id") private UUID runId;
    @TableField("evidence_id") private UUID evidenceId;
    @TableField("citation_order") private Integer citationOrder;
    @TableField("created_at") private Instant createdAt;
}
