package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 不含完整问题、隐藏提示和证据正文的运行查询快照。 */
public record AgentRunSnapshot(
        UUID runId,
        String operatorId,
        String idempotencyKey,
        String requestHash,
        String taskType,
        AgentRunStatus status,
        AgentResultType resultType,
        String resultText,
        AgentRefusalReason refusalReason,
        AgentErrorCode errorCode,
        AgentScopeSnapshot scope,
        AgentVersionSnapshot versions,
        int questionLength,
        int stepCount,
        int modelCallCount,
        Long inputTokens,
        Long outputTokens,
        Instant acceptedAt,
        Instant startedAt,
        Instant finishedAt,
        List<AgentCitationSnapshot> citations
) {
    public AgentRunSnapshot {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
