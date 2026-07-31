package io.github.loredock.agent.model.snapshot;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.enums.AnswerBasis;
import java.time.Instant;
import java.util.List;

/** 不含完整问题、隐藏提示和证据正文的运行查询快照。 */
public record AgentRunSnapshot(
        Long runId,
        String operatorId,
        String idempotencyKey,
        String requestHash,
        String taskType,
        AgentRunStatus status,
        AgentResultType resultType,
        AnswerBasis answerBasis,
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
    public AgentRunSnapshot(
            Long runId, String operatorId, String idempotencyKey, String requestHash, String taskType,
            AgentRunStatus status, AgentResultType resultType, String resultText,
            AgentRefusalReason refusalReason, AgentErrorCode errorCode, AgentScopeSnapshot scope,
            AgentVersionSnapshot versions, int questionLength, int stepCount, int modelCallCount,
            Long inputTokens, Long outputTokens, Instant acceptedAt, Instant startedAt, Instant finishedAt,
            List<AgentCitationSnapshot> citations
    ) {
        this(runId, operatorId, idempotencyKey, requestHash, taskType, status, resultType, null, resultText,
                refusalReason, errorCode, scope, versions, questionLength, stepCount, modelCallCount,
                inputTokens, outputTokens, acceptedAt, startedAt, finishedAt, citations);
    }

    public AgentRunSnapshot {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
