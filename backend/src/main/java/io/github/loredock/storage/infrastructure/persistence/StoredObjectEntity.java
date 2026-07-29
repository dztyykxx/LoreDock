package io.github.loredock.storage.infrastructure.persistence;

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

/**
 * `stored_object` 的 MyBatis-Plus 显式映射实体；结构只由 Flyway 管理。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stored_object")
public class StoredObjectEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    @TableField("object_key")
    private String objectKey;

    @TableField("status")
    private String status;

    @TableField("original_filename")
    private String originalFilename;

    @TableField("content_type")
    private String contentType;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("sha256")
    private String sha256;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
