package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/** 三个只读工具的有限调用摘要 Mapper。 */
@Mapper
public interface AgentToolCallMapper extends BaseMapper<AgentToolCallEntity> {

    /** @return 当前运行下一个单调调用序号；单 Agent 执行器保证同一运行串行调用。 */
    @Select("select coalesce(max(call_sequence), 0) + 1 from agent_tool_call where run_id = #{runId}")
    int nextSequence(@Param("runId") UUID runId);

    /** @return 仅从 RUNNING 更新到 SUCCEEDED 的行数 */
    @Update("""
            update agent_tool_call set status='SUCCEEDED', result_count=#{resultCount},
                evidence_count=#{evidenceCount}, finished_at=#{finishedAt}
            where id=#{callId} and status='RUNNING'
            """)
    int succeed(
            @Param("callId") UUID callId,
            @Param("resultCount") int resultCount,
            @Param("evidenceCount") int evidenceCount,
            @Param("finishedAt") Instant finishedAt
    );

    /** @return 仅从 RUNNING 更新到 FAILED 的行数 */
    @Update("""
            update agent_tool_call set status='FAILED', error_code=#{errorCode}, finished_at=#{finishedAt}
            where id=#{callId} and status='RUNNING'
            """)
    int fail(
            @Param("callId") UUID callId,
            @Param("errorCode") String errorCode,
            @Param("finishedAt") Instant finishedAt
    );
}
