package io.github.loredock.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 使用 MyBatis-Plus 与必要 PostgreSQL 行锁访问版本化草稿。 */
@Mapper
public interface KnowledgeDraftMapper extends BaseMapper<KnowledgeDraftEntity> {

    /** @return 已锁定且范围匹配的草稿 */
    @Select("""
            select id, conversation_id as "conversationId", operator_id as "operatorId",
                   project_id as "projectId", project_identifier as "projectIdentifier", title,
                   operation, baseline_document_id as "baselineDocumentId",
                   baseline_revision as "baselineRevision", directory_path as "directoryPath",
                   current_revision as "currentRevision",
                   create_run_id as "createRunId", create_idempotency_key as "createIdempotencyKey",
                   create_request_hash as "createRequestHash", published_document_id as "publishedDocumentId",
                   published_revision as "publishedRevision", created_at as "createdAt", updated_at as "updatedAt"
            from knowledge_draft
            where id = #{draftId} and operator_id = #{operatorId}
              and project_identifier = #{projectIdentifier} and conversation_id = #{conversationId}
            for update
            """)
    KnowledgeDraftEntity selectVisibleForUpdate(
            @Param("draftId") Long draftId,
            @Param("operatorId") String operatorId,
            @Param("projectIdentifier") String projectIdentifier,
            @Param("conversationId") Long conversationId
    );

    /** @return 当前修订成功从 expected 推进到 next 的行数 */
    @Update("""
            update knowledge_draft set current_revision = #{next}, updated_at = #{updatedAt}
            where id = #{draftId} and current_revision = #{expected}
            """)
    int advanceRevision(
            @Param("draftId") Long draftId,
            @Param("expected") long expected,
            @Param("next") long next,
            @Param("updatedAt") Instant updatedAt
    );

    /** @return ADD 工作文档的标题和审核修订指针同时更新成功的行数 */
    @Update("""
            update knowledge_draft
            set title = #{title}, current_revision = #{next}, updated_at = #{updatedAt}
            where id = #{draftId} and operation = 'ADD' and current_revision = #{expected}
              and published_document_id is null
            """)
    int renameAndAdvance(
            @Param("draftId") Long draftId,
            @Param("expected") long expected,
            @Param("next") long next,
            @Param("title") String title,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * 会话只指向其自身创建的当前草稿；projectId 为空时匹配全局任务会话。
     * projectId 必须显式声明 jdbcType：为 NULL 时若不带类型，PostgreSQL 无法推断参数类型而拒绝执行。
     */
    @Update("""
            update knowledge_task_conversation set current_draft_id = #{draftId}, updated_at = #{updatedAt}
            where id = #{conversationId} and operator_id = #{operatorId}
              and (
                (#{projectId,jdbcType=BIGINT} is null and project_id is null)
                or (#{projectId,jdbcType=BIGINT} is not null and project_id = #{projectId,jdbcType=BIGINT})
              )
            """)
    int attachConversationDraft(
            @Param("conversationId") Long conversationId,
            @Param("operatorId") String operatorId,
            @Param("projectId") Long projectId,
            @Param("draftId") Long draftId,
            @Param("updatedAt") Instant updatedAt
    );

    /** @return 尚未发布的当前审核修订成功绑定正式文档的行数 */
    @Update("""
            update knowledge_draft
            set published_document_id = #{documentId}, published_revision = #{revision}, updated_at = #{publishedAt}
            where id = #{draftId} and current_revision = #{revision} and published_document_id is null
            """)
    int markPublished(
            @Param("draftId") Long draftId,
            @Param("revision") long revision,
            @Param("documentId") Long documentId,
            @Param("publishedAt") Instant publishedAt
    );
}
