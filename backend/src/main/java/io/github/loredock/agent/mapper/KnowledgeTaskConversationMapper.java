package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 使用 MyBatis-Plus 与 PostgreSQL 幂等写入访问知识任务会话。 */
@Mapper
public interface KnowledgeTaskConversationMapper extends BaseMapper<KnowledgeTaskConversationEntity> {

    /** @return 新建会话 ID；同一操作者与幂等键已存在时为空 */
    @Select("""
            insert into knowledge_task_conversation(
                operator_id, idempotency_key, request_hash, project_id, project_identifier,
                trigger_type, trigger_reason, target_skill, goal, created_at, updated_at
            ) values (
                #{value.operatorId}, #{value.idempotencyKey}, #{value.requestHash},
                #{value.projectId}, #{value.projectIdentifier}, #{value.triggerType},
                #{value.triggerReason}, #{value.targetSkill}, #{value.goal},
                #{value.createdAt}, #{value.updatedAt}
            ) on conflict (operator_id, idempotency_key) do nothing
            returning id
            """)
    Long insertIfAbsent(@Param("value") KnowledgeTaskConversationEntity value);

    /** @return 同一操作者可见并已锁定的会话；用于串行化完成后继续 */
    @Select("""
            select id, operator_id as "operatorId", idempotency_key as "idempotencyKey",
                   request_hash as "requestHash", project_id as "projectId",
                   project_identifier as "projectIdentifier", trigger_type as "triggerType",
                   trigger_reason as "triggerReason", target_skill as "targetSkill", goal,
                   current_draft_id as "currentDraftId", created_at as "createdAt", updated_at as "updatedAt"
            from knowledge_task_conversation
            where id = #{conversationId} and operator_id = #{operatorId}
            for update
            """)
    KnowledgeTaskConversationEntity selectVisibleForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("operatorId") String operatorId
    );
}
