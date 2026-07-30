package io.github.loredock.knowledge.infrastructure.persistence;

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
 * `knowledge_index_generation` 的 MyBatis-Plus 显式映射实体，记录构建代次及其激活状态。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_index_generation")
public class KnowledgeIndexGenerationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

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
