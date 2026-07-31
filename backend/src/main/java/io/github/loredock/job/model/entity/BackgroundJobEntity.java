package io.github.loredock.job.model.entity;

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
 * `background_job` 的完整显式映射实体；领域状态机不依赖该可变数据结构。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("background_job")
public class BackgroundJobEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("job_type")
    private String jobType;

    @TableField("status")
    private String status;

    @TableField("progress")
    private Integer progress;

    @TableField("input_object_key")
    private String inputObjectKey;

    @TableField("project_id")
    private Long projectId;

    @TableField("branch_id")
    private Long branchId;

    @TableField("snapshot_id")
    private Long snapshotId;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("finished_at")
    private Instant finishedAt;

    @TableField("heartbeat_at")
    private Instant heartbeatAt;

    @TableField("owner_instance")
    private String ownerInstance;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
