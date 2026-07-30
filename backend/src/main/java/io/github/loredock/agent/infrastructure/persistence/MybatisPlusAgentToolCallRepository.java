package io.github.loredock.agent.infrastructure.persistence;

import io.github.loredock.agent.application.AgentToolCallRepository;
import io.github.loredock.agent.application.AgentToolCallStart;
import io.github.loredock.agent.domain.AgentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** MyBatis-Plus 工具调用仓储，每次状态写入都是不跨越实际检索的独立短事务。 */
@Repository
@Slf4j
public class MybatisPlusAgentToolCallRepository implements AgentToolCallRepository {

    private final AgentToolCallMapper calls;

    /** @param calls 工具调用显式 Mapper */
    public MybatisPlusAgentToolCallRepository(AgentToolCallMapper calls) {
        this.calls = calls;
    }

    @Override
    @Transactional
    public AgentToolCallStart start(
            UUID runId,
            String toolName,
            String safeArgumentSummary,
            Instant startedAt
    ) {
        int sequence = calls.nextSequence(runId);
        UUID callId = UUID.randomUUID();
        calls.insert(AgentToolCallEntity.builder()
                .id(callId).runId(runId).callSequence(sequence).toolName(toolName).status("RUNNING")
                .argumentSummary(safeArgumentSummary).resultCount(0).evidenceCount(0)
                .startedAt(startedAt).build());
        log.info("agent_tool_call started runId={} callSequence={} tool={}", runId, sequence, toolName);
        return new AgentToolCallStart(callId, sequence);
    }

    @Override
    @Transactional
    public void succeed(UUID callId, int resultCount, int evidenceCount, Instant finishedAt) {
        if (calls.succeed(callId, resultCount, evidenceCount, finishedAt) != 1) {
            throw new IllegalStateException("agent tool call terminal transition failed");
        }
    }

    @Override
    @Transactional
    public void fail(UUID callId, AgentErrorCode code, Instant finishedAt) {
        if (calls.fail(callId, code.name(), finishedAt) != 1) {
            throw new IllegalStateException("agent tool call terminal transition failed");
        }
    }
}
