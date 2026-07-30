package io.github.loredock.code.infrastructure.persistence;

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

/** `code_snapshot` 的完整显式映射实体；领域生命周期不依赖该可变持久化结构。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("code_snapshot")
public class CodeSnapshotEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    @TableField("project_id")
    private UUID projectId;

    @TableField("branch_id")
    private UUID branchId;

    @TableField("commit_hash")
    private String commitHash;

    @TableField("input_object_key")
    private String inputObjectKey;

    @TableField("status")
    private String status;

    @TableField("previous_snapshot_id")
    private UUID previousSnapshotId;

    @TableField("indexed_file_count")
    private Long indexedFileCount;

    @TableField("ignored_file_count")
    private Long ignoredFileCount;

    @TableField("indexed_at")
    private Instant indexedAt;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
