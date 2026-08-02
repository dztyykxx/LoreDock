package io.github.loredock.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.platform.persistence.PostgresJsonbTypeHandler;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** 不可变草稿修订；区块 JSON 与完整 Markdown 在同一提交中固化。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "knowledge_draft_revision", autoResultMap = true)
public class KnowledgeDraftRevisionEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("draft_id") private Long draftId;
    @TableField("revision") private Long revision;
    @TableField("markdown") private String markdown;
    @TableField(value = "blocks_json", jdbcType = JdbcType.OTHER, typeHandler = PostgresJsonbTypeHandler.class)
    private String blocksJson;
    @TableField("change_summary") private String changeSummary;
    @TableField("created_by_run_id") private Long createdByRunId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("request_hash") private String requestHash;
    @TableField("created_at") private Instant createdAt;
}
