package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/** 运行 Mapper；只有状态比较更新使用参数化注解 SQL，确保终态 CAS 在数据库执行。 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

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
