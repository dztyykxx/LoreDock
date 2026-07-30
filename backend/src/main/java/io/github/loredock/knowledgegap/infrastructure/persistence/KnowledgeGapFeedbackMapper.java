package io.github.loredock.knowledgegap.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/** 使用 MyBatis-Plus Java API 访问知识缺口反馈事实。 */
@Mapper
public interface KnowledgeGapFeedbackMapper extends BaseMapper<KnowledgeGapFeedbackEntity> {
    /** PostgreSQL 唯一键冲突必须返回未插入，避免异常把外层幂等事务标记为回滚。 */
    @Insert("""
            insert into knowledge_gap_feedback(
                id, operator_id, idempotency_key, request_hash,
                project_id, project_identifier, branch_id, branch_name,
                question_id, run_id, gap_type, status, question_text, note,
                result_type, refusal_reason, error_code,
                created_at, updated_at, created_by, updated_by)
            values(
                #{value.id}, #{value.operatorId}, #{value.idempotencyKey}, #{value.requestHash},
                #{value.projectId}, #{value.projectIdentifier}, #{value.branchId}, #{value.branchName},
                #{value.questionId}, #{value.runId}, #{value.gapType}, #{value.status},
                #{value.questionText}, #{value.note}, #{value.resultType}, #{value.refusalReason},
                #{value.errorCode}, #{value.createdAt}, #{value.updatedAt}, #{value.createdBy}, #{value.updatedBy})
            on conflict (operator_id, idempotency_key) do nothing
            """)
    int insertIfAbsent(@Param("value") KnowledgeGapFeedbackEntity entity);

    /** 状态和审计必须共享一个数据库比较更新，防止并发管理员覆盖彼此。 */
    @Update("""
            update knowledge_gap_feedback
            set status=#{target}, updated_at=#{updatedAt}, updated_by=#{actor}
            where id=#{id} and status=#{expected}
            """)
    int updateStatus(
            @Param("id") UUID id,
            @Param("expected") String expected,
            @Param("target") String target,
            @Param("actor") String actor,
            @Param("updatedAt") Instant updatedAt);
}
