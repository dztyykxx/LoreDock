package io.github.loredock.project.model.entity;

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
 * `project_space` 的 MyBatis-Plus 显式映射实体；领域规则不依赖该可变持久化结构。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("project_space")
public class ProjectSpaceEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("identifier")
    private String identifier;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("technology_stack")
    private String technologyStack;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
