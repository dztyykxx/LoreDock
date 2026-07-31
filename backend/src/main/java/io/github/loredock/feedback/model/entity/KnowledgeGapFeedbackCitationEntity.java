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

/** 知识缺口与同一 Agent 运行证据的有序关联显式持久化实体。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_gap_feedback_citation")
public class KnowledgeGapFeedbackCitationEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("feedback_id") private Long feedbackId;
    @TableField("run_id") private Long runId;
    @TableField("evidence_id") private Long evidenceId;
    @TableField("citation_order") private Integer citationOrder;
    @TableField("created_at") private Instant createdAt;
}
