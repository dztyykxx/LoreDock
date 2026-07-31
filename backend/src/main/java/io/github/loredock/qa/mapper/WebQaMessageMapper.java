package io.github.loredock.qa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.qa.model.entity.WebQaMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 使用 MyBatis-Plus Java API 访问 Web 问答消息投影。 */
@Mapper
public interface WebQaMessageMapper extends BaseMapper<WebQaMessageEntity> {
    /**
     * 唯一角色投影需要原子冲突忽略，避免并发详情读取把事务标记为回滚；Java Wrapper 无法表达该插入语义。
     * @return 数据库生成的消息 ID；角色已存在时为 null
     */
    @Select("""
            insert into web_qa_message(
                question_id, role, content, result_type, refusal_reason, created_at
            ) values (
                #{value.questionId}, #{value.role}, #{value.content},
                #{value.resultType}, #{value.refusalReason}, #{value.createdAt}
            ) on conflict (question_id, role) do nothing
            returning id
            """)
    Long insertIfAbsent(@Param("value") WebQaMessageEntity value);
}
