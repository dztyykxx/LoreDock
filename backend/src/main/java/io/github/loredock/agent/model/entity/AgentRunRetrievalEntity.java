package io.github.loredock.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.platform.persistence.PostgresJsonbTypeHandler;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.type.JdbcType;

/** `agent_run_retrieval` 的显式映射实体，保存每次知识检索实际提供给模型的查询与文档片段。 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@TableName(value = "agent_run_retrieval", autoResultMap = true)
public class AgentRunRetrievalEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField("run_id") private Long runId;
    @TableField("sequence_no") private Integer sequenceNo;
    @TableField("query_text") private String queryText;
    @TableField(value = "documents", jdbcType = JdbcType.OTHER, typeHandler = PostgresJsonbTypeHandler.class)
    private String documents;
    @TableField("created_at") private Instant createdAt;
}
