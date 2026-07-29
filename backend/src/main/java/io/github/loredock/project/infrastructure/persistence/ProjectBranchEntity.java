package io.github.loredock.project.infrastructure.persistence;

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
 * `project_branch` 的 MyBatis-Plus 显式映射实体；名称保留大小写但不得用作文件路径。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("project_branch")
public class ProjectBranchEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    @TableField("project_id")
    private UUID projectId;

    @TableField("name")
    private String name;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
