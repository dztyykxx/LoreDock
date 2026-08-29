package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 运行 Mapper；只有状态比较更新使用参数化注解 SQL，确保终态 CAS 在数据库执行。 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /**
     * Agent 受理可能加入 Web 外层事务；冲突忽略可避免 PostgreSQL 唯一键异常把整个事务标记为失败。
     * @return 数据库生成的运行 ID；冲突时为 null
     */
    @Select("""
            insert into agent_run(
                operator_id, idempotency_key, request_hash, task_type, question_hash, question_length,
                project_id, project_identifier, branch_id, branch_name, snapshot_id, commit_hash,
                knowledge_generation_id, agent_name, model_name, config_summary,
                status, event_sequence, step_count, model_call_count, retrieval_count, trimmed_character_count,
                accepted_at, updated_at
            ) values (
                #{value.operatorId}, #{value.idempotencyKey}, #{value.requestHash}, #{value.taskType},
                #{value.questionHash}, #{value.questionLength}, #{value.projectId}, #{value.projectIdentifier},
                #{value.branchId}, #{value.branchName}, #{value.snapshotId}, #{value.commitHash},
                #{value.knowledgeGenerationId}, #{value.agentName}, #{value.modelName}, #{value.configSummary},
                #{value.status}, #{value.eventSequence},
                #{value.stepCount}, #{value.modelCallCount}, #{value.retrievalCount},
                #{value.trimmedCharacterCount}, #{value.acceptedAt}, #{value.updatedAt}
            ) on conflict (operator_id, idempotency_key) do nothing
            returning id
            """)
    Long insertIfAbsent(@Param("value") AgentRunEntity value);

    /** @return 复用既有 agent_run 创建知识长任务运行的数据库 ID */
    @Select("""
            insert into agent_run(
                operator_id, idempotency_key, request_hash, task_type, question_hash, question_length,
                project_id, project_identifier, branch_id, branch_name, knowledge_generation_id,
                agent_name, model_name, config_summary,
                status, event_sequence, step_count, model_call_count, tool_call_count,
                retrieval_count, trimmed_character_count, knowledge_task_conversation_id, thread_id,
                skill_digest, agent_spec_digest, tool_names,
                accepted_at, updated_at
            ) values (
                #{value.operatorId}, #{value.idempotencyKey}, #{value.requestHash}, #{value.taskType},
                #{value.questionHash}, #{value.questionLength}, #{value.projectId}, #{value.projectIdentifier},
                #{value.branchId}, #{value.branchName}, #{value.knowledgeGenerationId},
                #{value.agentName}, #{value.modelName},
                #{value.configSummary}, #{value.status}, #{value.eventSequence}, #{value.stepCount},
                #{value.modelCallCount}, #{value.toolCallCount}, #{value.retrievalCount},
                #{value.trimmedCharacterCount}, #{value.knowledgeTaskConversationId}, #{value.threadId},
                #{value.skillDigest}, #{value.agentSpecDigest}, #{value.toolNames},
                #{value.acceptedAt}, #{value.updatedAt}
            ) returning id
            """)
    Long insertKnowledgeRun(@Param("value") AgentRunEntity value);

    /** @return 运行中或已受理知识任务成功投影为请求暂停的行数 */
    @Update("""
            update agent_run set status = 'PAUSE_REQUESTED', updated_at = #{updatedAt}
            where id = #{runId} and operator_id = #{operatorId}
              and task_type = 'knowledge_curation' and status in ('ACCEPTED', 'RUNNING')
            """)
    int requestKnowledgePause(
            @Param("runId") Long runId,
            @Param("operatorId") String operatorId,
            @Param("updatedAt") Instant updatedAt
    );

    /** @return 非终态知识运行成功停止的行数 */
    @Update("""
            update agent_run set status = 'CANCELLED', error_code = 'AGENT_RUN_CANCELLED',
                   finished_at = #{finishedAt}, updated_at = #{finishedAt}
            where id = #{runId} and operator_id = #{operatorId}
              and task_type = 'knowledge_curation'
              and status in ('ACCEPTED', 'RUNNING', 'PAUSE_REQUESTED', 'WAITING_FOR_USER')
            """)
    int cancelKnowledge(
            @Param("runId") Long runId,
            @Param("operatorId") String operatorId,
            @Param("finishedAt") Instant finishedAt
    );

    /** @return 已有可靠 Checkpoint 的等待运行成功恢复为 RUNNING 的行数 */
    @Update("""
            update agent_run set status = 'RUNNING', started_at = coalesce(started_at, #{updatedAt}),
                   updated_at = #{updatedAt}
            where id = #{runId} and operator_id = #{operatorId}
              and task_type = 'knowledge_curation' and status = 'WAITING_FOR_USER'
              and checkpoint_saved_at is not null
            """)
    int resumeKnowledgeRun(
            @Param("runId") Long runId,
            @Param("operatorId") String operatorId,
            @Param("updatedAt") Instant updatedAt
    );

    /** @return 已请求暂停的 run 在 Checkpoint 提交后成功投影为等待人工的行数 */
    @Update("""
            update agent_run set status = 'WAITING_FOR_USER', checkpoint_saved_at = #{savedAt},
                   updated_at = #{savedAt}
            where id = #{runId} and task_type = 'knowledge_curation' and status = 'PAUSE_REQUESTED'
            """)
    int markKnowledgeWaiting(@Param("runId") Long runId, @Param("savedAt") Instant savedAt);

    /** @return 知识任务从受理态进入框架执行的行数 */
    @Update("""
            update agent_run set status = 'RUNNING', started_at = #{startedAt}, updated_at = #{startedAt}
            where id = #{runId} and task_type = 'knowledge_curation' and status = 'ACCEPTED'
            """)
    int markKnowledgeRunning(@Param("runId") Long runId, @Param("startedAt") Instant startedAt);

    /** @return 框架正常结束的知识任务成功提交终态的行数 */
    @Update("""
            update agent_run set status = 'COMPLETED', result_text = #{resultText}, error_code = null,
                   step_count = #{stepCount}, model_call_count = #{modelCallCount},
                   tool_call_count = #{toolCallCount}, elapsed_millis = #{elapsedMillis},
                   input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                   finished_at = #{finishedAt}, updated_at = #{finishedAt}
            where id = #{runId} and task_type = 'knowledge_curation' and status in ('RUNNING', 'PAUSE_REQUESTED')
            """)
    int completeKnowledge(
            @Param("runId") Long runId,
            @Param("resultText") String resultText,
            @Param("stepCount") int stepCount,
            @Param("modelCallCount") int modelCallCount,
            @Param("toolCallCount") int toolCallCount,
            @Param("elapsedMillis") long elapsedMillis,
            @Param("inputTokens") Long inputTokens,
            @Param("outputTokens") Long outputTokens,
            @Param("finishedAt") Instant finishedAt
    );

    /** @return 非终态知识任务成功保存真实失败终态的行数 */
    @Update("""
            update agent_run set status = 'FAILED', error_code = #{errorCode},
                   step_count = #{stepCount}, model_call_count = #{modelCallCount},
                   tool_call_count = #{toolCallCount}, elapsed_millis = #{elapsedMillis},
                   finished_at = #{finishedAt}, updated_at = #{finishedAt}
            where id = #{runId} and task_type = 'knowledge_curation'
              and status in ('ACCEPTED', 'RUNNING', 'PAUSE_REQUESTED')
            """)
    int failKnowledge(
            @Param("runId") Long runId,
            @Param("errorCode") String errorCode,
            @Param("stepCount") int stepCount,
            @Param("modelCallCount") int modelCallCount,
            @Param("toolCallCount") int toolCallCount,
            @Param("elapsedMillis") long elapsedMillis,
            @Param("finishedAt") Instant finishedAt
    );

    /** @return ACCEPTED 成功进入 RUNNING 的行数 */
    @Update("""
            update agent_run
            set status = 'RUNNING', started_at = #{startedAt}, updated_at = #{startedAt}
            where id = #{runId} and status = 'ACCEPTED'
            """)
    int markRunning(@Param("runId") Long runId, @Param("startedAt") Instant startedAt);

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
            @Param("runId") Long runId,
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
            @Param("runId") Long runId,
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
