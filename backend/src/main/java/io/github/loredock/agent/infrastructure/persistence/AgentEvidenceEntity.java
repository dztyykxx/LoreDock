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

/** `agent_evidence` 的显式映射实体，只保存来源与裁剪状态等有限元数据。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "agent_evidence", autoResultMap = true)
public class AgentEvidenceEntity {
    @TableId(value = "id", type = IdType.INPUT) private UUID id;
    @TableField("run_id") private UUID runId;
    @TableField("evidence_key") private String evidenceKey;
    @TableField("source_type") private String sourceType;
    @TableField("retained") private Boolean retained;
    @TableField("relevance") private Double relevance;
    @TableField("document_id") private UUID documentId;
    @TableField("snapshot_id") private UUID snapshotId;
    @TableField("project_identifier") private String projectIdentifier;
    @TableField("branch_name") private String branchName;
    @TableField("commit_hash") private String commitHash;
    @TableField("repository_path") private String repositoryPath;
    @TableField("title") private String title;
    @TableField("source_updated_at") private Instant sourceUpdatedAt;
    @TableField(value = "metadata", jdbcType = JdbcType.OTHER, typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    @TableField("created_at") private Instant createdAt;
}
