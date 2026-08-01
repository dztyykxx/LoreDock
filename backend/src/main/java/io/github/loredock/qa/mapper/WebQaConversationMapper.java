package io.github.loredock.qa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.qa.model.entity.WebQaConversationEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 使用 MyBatis-Plus 和必要的 PostgreSQL 行锁访问 QA 会话。 */
@Mapper
public interface WebQaConversationMapper extends BaseMapper<WebQaConversationEntity> {

    /** @return 新建会话的数据库标识 */
    @Select("""
            insert into web_qa_conversation(
                operator_id, project_id, project_identifier, title, created_at, updated_at, last_question_at
            ) values (
                #{value.operatorId}, #{value.projectId}, #{value.projectIdentifier}, #{value.title},
                #{value.createdAt}, #{value.updatedAt}, #{value.lastQuestionAt}
            ) returning id
            """)
    Long insertReturning(@Param("value") WebQaConversationEntity value);

    /**
     * 追加轮次前锁定同一操作者与项目可见的会话，串行化“检查活动轮次—创建新轮次”。
     */
    @Select("""
            select id, operator_id as "operatorId", project_id as "projectId",
                   project_identifier as "projectIdentifier", title,
                   created_at as "createdAt", updated_at as "updatedAt",
                   last_question_at as "lastQuestionAt"
            from web_qa_conversation
            where id = #{conversationId} and operator_id = #{operatorId} and project_id = #{projectId}
            for update
            """)
    WebQaConversationEntity selectVisibleForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("operatorId") String operatorId,
            @Param("projectId") Long projectId
    );

    /** @return 删除的未绑定问题会话数，有轮次时稳定返回 0 */
    @Delete("""
            delete from web_qa_conversation conversation
            where conversation.id = #{conversationId}
              and not exists (
                  select 1 from web_qa_question question
                  where question.conversation_id = conversation.id
              )
            """)
    int deleteEmpty(@Param("conversationId") Long conversationId);
}
