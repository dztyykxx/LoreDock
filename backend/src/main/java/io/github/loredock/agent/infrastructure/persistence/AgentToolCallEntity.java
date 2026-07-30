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

/** `agent_tool_call` 的显式映射实体，不保存原始检索正文和敏感参数。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "agent_tool_call", autoResultMap = true)
public class AgentToolCallEntity {
    @TableId(value = "id", type = IdType.INPUT) private UUID id;
    @TableField("run_id") private UUID runId;
    @TableField("call_sequence") private Integer callSequence;
    @TableField("tool_name") private String toolName;
    @TableField("status") private String status;
    @TableField(value = "argument_summary", jdbcType = JdbcType.OTHER, typeHandler = PostgresJsonbTypeHandler.class)
    private String argumentSummary;
    @TableField("result_count") private Integer resultCount;
    @TableField("evidence_count") private Integer evidenceCount;
    @TableField("error_code") private String errorCode;
    @TableField("started_at") private Instant startedAt;
    @TableField("finished_at") private Instant finishedAt;
}
