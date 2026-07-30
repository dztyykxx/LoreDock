package io.github.loredock.qa.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 使用 MyBatis-Plus Java API 访问 Web 问答消息投影。 */
@Mapper
public interface WebQaMessageMapper extends BaseMapper<WebQaMessageEntity> {
    /**
     * 唯一角色投影需要原子冲突忽略，避免并发详情读取把事务标记为回滚；Java Wrapper 无法表达该插入语义。
     * @return 实际插入行数
     */
    @Insert("""
            insert into web_qa_message(
                id, question_id, role, content, result_type, refusal_reason, created_at
            ) values (
                #{value.id}, #{value.questionId}, #{value.role}, #{value.content},
                #{value.resultType}, #{value.refusalReason}, #{value.createdAt}
            ) on conflict (question_id, role) do nothing
            """)
    int insertIfAbsent(@Param("value") WebQaMessageEntity value);
}
