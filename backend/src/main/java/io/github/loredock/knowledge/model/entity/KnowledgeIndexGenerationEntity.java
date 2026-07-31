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
 * `knowledge_index_generation` 的 MyBatis-Plus 显式映射实体，记录构建代次及其激活状态。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_index_generation")
public class KnowledgeIndexGenerationEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("job_id")
    private Long jobId;

    @TableField("status")
    private String status;

    @TableField("model_id")
    private String modelId;

    @TableField("model_checksum")
    private String modelChecksum;

    @TableField("vector_dimension")
    private Integer vectorDimension;

    @TableField("chunk_strategy_version")
    private String chunkStrategyVersion;

    @TableField("fusion_config_version")
    private String fusionConfigVersion;

    @TableField("document_count")
    private Long documentCount;

    @TableField("chunk_count")
    private Long chunkCount;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("activated_at")
    private Instant activatedAt;
}
