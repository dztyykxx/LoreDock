package io.github.loredock.qa.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 使用 MyBatis-Plus Java API 访问 Web 问答身份事实。 */
@Mapper
public interface WebQaQuestionMapper extends BaseMapper<WebQaQuestionEntity> {
    /**
     * PostgreSQL 的冲突忽略能在并发唯一键竞争中保留事务可用性，MyBatis-Plus Java API 无法表达该语义。
     * @return 实际插入行数
     */
    @Insert("""
            insert into web_qa_question(
                id, operator_id, idempotency_key, request_hash, project_id, project_identifier,
                branch_id, branch_name, run_id, created_at
            ) values (
                #{value.id}, #{value.operatorId}, #{value.idempotencyKey}, #{value.requestHash},
                #{value.projectId}, #{value.projectIdentifier}, #{value.branchId}, #{value.branchName},
                #{value.runId}, #{value.createdAt}
            ) on conflict (operator_id, idempotency_key) do nothing
            """)
    int insertIfAbsent(@Param("value") WebQaQuestionEntity value);
}
