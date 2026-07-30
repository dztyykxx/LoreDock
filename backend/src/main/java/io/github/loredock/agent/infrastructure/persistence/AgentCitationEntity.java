package io.github.loredock.agent.infrastructure.persistence;

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

/** `agent_citation` 的显式映射实体，通过 V6 复合外键限制为同一运行证据。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("agent_citation")
public class AgentCitationEntity {
    @TableId(value = "id", type = IdType.INPUT) private UUID id;
    @TableField("run_id") private UUID runId;
    @TableField("evidence_id") private UUID evidenceId;
    @TableField("citation_order") private Integer citationOrder;
    @TableField("created_at") private Instant createdAt;
}
