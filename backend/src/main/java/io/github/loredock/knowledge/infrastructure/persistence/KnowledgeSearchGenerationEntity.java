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

/** `knowledge_search_generation` 的显式映射实体，保存一次完整检索构建使用的固定配置与计数。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_search_generation")
public class KnowledgeSearchGenerationEntity {

    @TableId(value = "generation_id", type = IdType.INPUT)
    private UUID generationId;

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
}
