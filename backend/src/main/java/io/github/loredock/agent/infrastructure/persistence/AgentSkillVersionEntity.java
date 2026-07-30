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

/** `agent_skill_version` 的显式映射实体，只保存内置 Skill 的版本化元数据。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("agent_skill_version")
public class AgentSkillVersionEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("skill_name")
    private String skillName;
    @TableField("skill_version")
    private String skillVersion;
    @TableField("content_hash")
    private String contentHash;
    @TableField("object_key")
    private String objectKey;
    @TableField("output_schema_version")
    private String outputSchemaVersion;
    @TableField("status")
    private String status;
    @TableField("created_at")
    private Instant createdAt;
}
