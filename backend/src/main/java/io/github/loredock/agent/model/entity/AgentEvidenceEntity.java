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

/** `agent_evidence` 的显式映射实体，只保存来源与裁剪状态等有限元数据。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "agent_evidence", autoResultMap = true)
public class AgentEvidenceEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("run_id") private Long runId;
    @TableField("evidence_key") private String evidenceKey;
    @TableField("source_type") private String sourceType;
    @TableField("retained") private Boolean retained;
    @TableField("cited") private Boolean cited;
    @TableField("citation_order") private Integer citationOrder;
    @TableField("relevance") private Double relevance;
    @TableField("document_id") private Long documentId;
    @TableField("snapshot_id") private Long snapshotId;
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
