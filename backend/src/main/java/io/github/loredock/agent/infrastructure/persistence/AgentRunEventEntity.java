package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.platform.persistence.PostgresJsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

/** `agent_run_event` 的显式映射实体，payload 仅允许公开摘要或最终文本增量。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "agent_run_event", autoResultMap = true)
public class AgentRunEventEntity {
    @TableId(value = "id", type = IdType.INPUT) private UUID id;
    @TableField("run_id") private UUID runId;
    @TableField("sequence") private Long sequence;
    @TableField("event_type") private String eventType;
    @TableField(value = "payload", jdbcType = JdbcType.OTHER, typeHandler = PostgresJsonbTypeHandler.class)
    private String payload;
    @TableField("created_at") private Instant createdAt;
}
