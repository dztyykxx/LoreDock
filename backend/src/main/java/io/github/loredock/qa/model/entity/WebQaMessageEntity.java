package io.github.loredock.qa.model.entity;

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

/** Web 问答唯一用户消息和终态公开消息的显式持久化实体。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("web_qa_message")
public class WebQaMessageEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("question_id") private Long questionId;
    @TableField("role") private String role;
    @TableField("content") private String content;
    @TableField("result_type") private String resultType;
    @TableField("refusal_reason") private String refusalReason;
    @TableField("created_at") private Instant createdAt;
}
