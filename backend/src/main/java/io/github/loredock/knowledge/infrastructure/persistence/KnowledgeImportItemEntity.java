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

import java.util.UUID;

/**
 * `knowledge_import_item` 的 MyBatis-Plus 显式映射实体，保存每个归档条目的处理结果。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_import_item")
public class KnowledgeImportItemEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    @TableField("batch_id")
    private UUID batchId;

    @TableField("ordinal")
    private Integer ordinal;

    @TableField("entry_name")
    private String entryName;

    @TableField("status")
    private String status;

    @TableField("reason_code")
    private String reasonCode;

    @TableField("message")
    private String message;

    @TableField("document_id")
    private UUID documentId;
}
