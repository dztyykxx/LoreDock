package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/** 运行 Mapper；只有状态比较更新使用参数化注解 SQL，确保终态 CAS 在数据库执行。 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /**
     * Agent 受理可能加入 Web 外层事务；冲突忽略可避免 PostgreSQL 唯一键异常把整个事务标记为失败。
     * @return 实际插入行数
     */
    @Insert("""
            insert into agent_run(
                id, operator_id, idempotency_key, request_hash, task_type, question_hash, question_length,
                project_id, project_identifier, branch_id, branch_name, snapshot_id, commit_hash,
                knowledge_generation_id, skill_version_id, skill_name, skill_version, skill_content_hash,
                model_provider, model_name, output_schema_version, tool_policy_version, limit_policy_version,
                status, event_sequence, step_count, model_call_count, retrieval_count, trimmed_character_count,
                accepted_at, updated_at
            ) values (
                #{value.id}, #{value.operatorId}, #{value.idempotencyKey}, #{value.requestHash}, #{value.taskType},
                #{value.questionHash}, #{value.questionLength}, #{value.projectId}, #{value.projectIdentifier},
                #{value.branchId}, #{value.branchName}, #{value.snapshotId}, #{value.commitHash},
                #{value.knowledgeGenerationId}, #{value.skillVersionId}, #{value.skillName}, #{value.skillVersion},
                #{value.skillContentHash}, #{value.modelProvider}, #{value.modelName}, #{value.outputSchemaVersion},
                #{value.toolPolicyVersion}, #{value.limitPolicyVersion}, #{value.status}, #{value.eventSequence},
                #{value.stepCount}, #{value.modelCallCount}, #{value.retrievalCount},
                #{value.trimmedCharacterCount}, #{value.acceptedAt}, #{value.updatedAt}
            ) on conflict (operator_id, idempotency_key) do nothing
            """)
    int insertIfAbsent(@Param("value") AgentRunEntity value);

    /** @return ACCEPTED 成功进入 RUNNING 的行数 */
    @Update("""
            update agent_run
            set status = 'RUNNING', started_at = #{startedAt}, updated_at = #{startedAt}
            where id = #{runId} and status = 'ACCEPTED'
            """)
    int markRunning(@Param("runId") UUID runId, @Param("startedAt") Instant startedAt);

    /** @return RUNNING 成功写入可信完成结果的行数 */
    @Update("""
            update agent_run
            set status = 'COMPLETED', result_type = #{resultType}, answer_basis = #{answerBasis},
                result_text = #{resultText},
                refusal_reason = #{refusalReason}, error_code = null,
                step_count = #{stepCount}, model_call_count = #{modelCallCount},
                retrieval_count = #{retrievalCount}, trimmed_character_count = #{trimmedCharacterCount},
                input_tokens = #{inputTokens}, output_tokens = #{outputTokens}, elapsed_millis = #{elapsedMillis},
                finished_at = #{finishedAt}, updated_at = #{finishedAt}
            where id = #{runId} and status = 'RUNNING'
            """)
    int complete(
            @Param("runId") UUID runId,
            @Param("resultType") String resultType,
            @Param("answerBasis") String answerBasis,
            @Param("resultText") String resultText,
            @Param("refusalReason") String refusalReason,
            @Param("stepCount") int stepCount,
            @Param("modelCallCount") int modelCallCount,
            @Param("retrievalCount") int retrievalCount,
            @Param("trimmedCharacterCount") int trimmedCharacterCount,
            @Param("inputTokens") Long inputTokens,
            @Param("outputTokens") Long outputTokens,
            @Param("elapsedMillis") long elapsedMillis,
            @Param("finishedAt") Instant finishedAt
    );

    /** @return 非终态成功进入 FAILED 或 TERMINATED 的行数 */
    @Update("""
            update agent_run
            set status = #{targetStatus}, error_code = #{errorCode},
                step_count = #{stepCount}, model_call_count = #{modelCallCount},
                retrieval_count = #{retrievalCount}, trimmed_character_count = #{trimmedCharacterCount},
                input_tokens = #{inputTokens}, output_tokens = #{outputTokens}, elapsed_millis = #{elapsedMillis},
                finished_at = #{finishedAt}, updated_at = #{finishedAt}
            where id = #{runId} and status in ('ACCEPTED', 'RUNNING')
            """)
    int finishWithError(
            @Param("runId") UUID runId,
            @Param("targetStatus") String targetStatus,
            @Param("errorCode") String errorCode,
            @Param("stepCount") int stepCount,
            @Param("modelCallCount") int modelCallCount,
            @Param("retrievalCount") int retrievalCount,
            @Param("trimmedCharacterCount") int trimmedCharacterCount,
            @Param("inputTokens") Long inputTokens,
            @Param("outputTokens") Long outputTokens,
            @Param("elapsedMillis") long elapsedMillis,
            @Param("finishedAt") Instant finishedAt
    );
}
