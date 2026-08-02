package io.github.loredock.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 草稿修订实际使用的去重证据或用户消息来源。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_draft_revision_source")
public class KnowledgeDraftRevisionSourceEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("revision_id") private Long revisionId;
    @TableField("source_type") private String sourceType;
    @TableField("source_id") private Long sourceId;
}
