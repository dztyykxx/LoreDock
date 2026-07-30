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

/** `code_index_generation` 的完整显式映射实体；物理目录只能由 generation UUID 派生。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("code_index_generation")
public class CodeIndexGenerationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    @TableField("snapshot_id")
    private UUID snapshotId;

    @TableField("job_id")
    private UUID jobId;

    @TableField("status")
    private String status;

    @TableField("document_count")
    private Long documentCount;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("activated_at")
    private Instant activatedAt;
}
