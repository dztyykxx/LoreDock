package io.github.loredock.agent.model.entity;

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

/** 知识合并会话启动时固定的待处理草稿关系。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_task_selected_draft")
public class KnowledgeTaskSelectedDraftEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("conversation_id") private Long conversationId;
    @TableField("document_id") private Long documentId;
    @TableField("document_revision") private Long documentRevision;
    @TableField("title") private String title;
    @TableField("directory_path") private String directoryPath;
    @TableField("markdown") private String markdown;
    @TableField("original_filename") private String originalFilename;
    @TableField("ordinal") private Integer ordinal;
    @TableField("created_at") private Instant createdAt;
}
