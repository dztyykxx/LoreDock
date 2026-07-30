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
 * `knowledge_document_tag` 的显式映射实体；复合主键由数据库保护，文档标识作为 MyBatis-Plus 输入主键。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_document_tag")
public class KnowledgeDocumentTagEntity {

    @TableId(value = "document_id", type = IdType.INPUT)
    private UUID documentId;

    @TableField("normalized_name")
    private String normalizedName;

    @TableField("display_name")
    private String displayName;
}
