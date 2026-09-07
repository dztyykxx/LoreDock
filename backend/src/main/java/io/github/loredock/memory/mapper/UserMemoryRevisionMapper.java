package io.github.loredock.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.memory.model.entity.UserMemoryRevisionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户记忆历史版本 Mapper。 */
@Mapper
public interface UserMemoryRevisionMapper extends BaseMapper<UserMemoryRevisionEntity> {

    /** JSONB 快照显式转换，避免 PostgreSQL 将 Java String 当作 varchar。 */
    @Insert("""
            insert into user_memory_revision
                (memory_id, revision, snapshot, operation, relation, reason,
                 source_run_id, source_conversation_id, source_message_id, operator_id, created_at)
            values (#{memoryId}, #{revision}, cast(#{snapshot} as jsonb), #{operation}, #{relation}, #{reason},
                    #{sourceRunId}, #{sourceConversationId}, #{sourceMessageId}, #{operatorId}, #{createdAt})
            """)
    int insertRevision(UserMemoryRevisionEntity entity);

    /** 查询指定记忆的版本历史，正文快照按版本倒序返回。 */
    @Select("""
            select * from user_memory_revision
            where memory_id = #{memoryId}
            order by revision desc, id desc
            limit #{size} offset #{offset}
            """)
    @Results(id = "userMemoryRevisionResult", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "memory_id", property = "memoryId"),
            @Result(column = "revision", property = "revision"),
            @Result(column = "snapshot", property = "snapshot"),
            @Result(column = "operation", property = "operation"),
            @Result(column = "relation", property = "relation"),
            @Result(column = "reason", property = "reason"),
            @Result(column = "source_run_id", property = "sourceRunId"),
            @Result(column = "source_conversation_id", property = "sourceConversationId"),
            @Result(column = "source_message_id", property = "sourceMessageId"),
            @Result(column = "operator_id", property = "operatorId"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<UserMemoryRevisionEntity> selectPageByMemoryId(@Param("memoryId") Long memoryId,
            @Param("size") int size, @Param("offset") long offset);

    /** @return 指定记忆历史版本总数 */
    @Select("select count(*) from user_memory_revision where memory_id = #{memoryId}")
    long countByMemoryId(@Param("memoryId") Long memoryId);

    /** 统计 Agent run 已提交的 CREATE/UPDATE 版本，供写入预算使用。 */
    @Select("select count(*) from user_memory_revision where source_run_id = #{sourceRunId} and operation in ('CREATE', 'UPDATE')")
    long countBySourceRun(@Param("sourceRunId") Long sourceRunId);

    /** 删除前擦除历史正文，但保留最小版本元数据，避免恢复正文且不释放写入预算。 */
    @Update("""
            update user_memory_revision
            set snapshot = '{}'::jsonb, reason = '记忆已删除', relation = null,
                source_run_id = null, source_conversation_id = null, source_message_id = null,
                operator_id = null
            where memory_id = #{memoryId}
            """)
    int sanitizeBeforeDelete(@Param("memoryId") Long memoryId);
}
