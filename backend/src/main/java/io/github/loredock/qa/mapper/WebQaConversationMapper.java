package io.github.loredock.qa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.qa.model.entity.WebQaConversationEntity;
import io.github.loredock.qa.model.result.WebQaGlobalConversationRecord;
import java.util.List;
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
     * 追加轮次前锁定同一操作者与范围可见的会话，串行化“检查活动轮次—创建新轮次”；
     * projectId 为空时只匹配 GLOBAL 会话（project_id IS NULL），与项目会话互斥。
     * projectId 必须显式声明 jdbcType：为 NULL 时若不带类型，PostgreSQL 无法推断参数类型而拒绝执行。
     */
    @Select("""
            select id, operator_id as "operatorId", project_id as "projectId",
                   project_identifier as "projectIdentifier", title,
                   created_at as "createdAt", updated_at as "updatedAt",
                   last_question_at as "lastQuestionAt"
            from web_qa_conversation
            where id = #{conversationId} and operator_id = #{operatorId}
              and (
                (#{projectId,jdbcType=BIGINT} is null and project_id is null)
                or (#{projectId,jdbcType=BIGINT} is not null and project_id = #{projectId,jdbcType=BIGINT})
              )
            for update
            """)
    WebQaConversationEntity selectVisibleForUpdate(
            @Param("conversationId") Long conversationId,
            @Param("operatorId") String operatorId,
            @Param("projectId") Long projectId
    );

    /**
     * 读取当前操作者全部范围（GLOBAL 与所有项目）的最近会话，附带项目显示名。
     *
     * @param afterCreatedAt 可选游标时间；与 afterId 一起构成稳定倒序边界
     * @param afterId 可选游标 ID
     * @return 严格受操作者限制的跨项目会话行
     */
    @Select("""
            <script>
            select conversation.id, conversation.operator_id as "operatorId",
                   conversation.project_id as "projectId",
                   conversation.project_identifier as "projectIdentifier",
                   project.name as "projectName", conversation.title,
                   conversation.created_at as "createdAt", conversation.updated_at as "updatedAt",
                   conversation.last_question_at as "lastQuestionAt"
            from web_qa_conversation conversation
            left join project_space project on project.id = conversation.project_id
            where conversation.operator_id = #{operatorId}
            <if test="afterCreatedAt != null and afterId != null">
              and (
                conversation.last_question_at &lt; #{afterCreatedAt}
                or (conversation.last_question_at = #{afterCreatedAt} and conversation.id &lt; #{afterId})
              )
            </if>
            order by conversation.last_question_at desc, conversation.id desc
            limit #{limit}
            </script>
            """)
    List<WebQaGlobalConversationRecord> findGlobalHistory(
            @Param("operatorId") String operatorId,
            @Param("afterCreatedAt") java.time.Instant afterCreatedAt,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
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
