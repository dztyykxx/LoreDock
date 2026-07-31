package io.github.loredock.agent.model.entity;

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

/** `agent_run_event` 的显式映射实体，payload 只允许粗粒度阶段、数量和稳定终态摘要。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "agent_run_event", autoResultMap = true)
public class AgentRunEventEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("run_id") private Long runId;
    @TableField("sequence") private Long sequence;
    @TableField("event_type") private String eventType;
    @TableField(value = "payload", jdbcType = JdbcType.OTHER, typeHandler = PostgresJsonbTypeHandler.class)
    private String payload;
    @TableField("created_at") private Instant createdAt;
}
