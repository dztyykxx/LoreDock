package io.github.loredock.knowledge.model.entity;

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

/**
 * `knowledge_import_batch` 的 MyBatis-Plus 显式映射实体，保存导入批次汇总和对象证据引用。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_import_batch")
public class KnowledgeImportBatchEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("object_key")
    private String objectKey;

    @TableField("original_filename")
    private String originalFilename;

    @TableField("scope_type")
    private String scopeType;

    @TableField("project_id")
    private Long projectId;

    @TableField("branch_id")
    private Long branchId;

    @TableField("directory_prefix")
    private String directoryPrefix;

    @TableField("status")
    private String status;

    @TableField("succeeded_count")
    private Integer succeededCount;

    @TableField("failed_count")
    private Integer failedCount;

    @TableField("ignored_count")
    private Integer ignoredCount;

    @TableField("results_json")
    private String resultsJson;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
