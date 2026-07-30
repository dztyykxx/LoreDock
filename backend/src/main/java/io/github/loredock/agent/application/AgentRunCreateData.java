package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;

import java.time.Instant;
import java.util.UUID;

/** 首次持久化运行所需的完整但已脱敏数据。 */
public record AgentRunCreateData(
        UUID runId,
        String operatorId,
        String idempotencyKey,
        String requestHash,
        String taskType,
        String questionHash,
        int questionLength,
        AgentScopeSnapshot scope,
        AgentVersionSnapshot versions,
        Instant acceptedAt
) {
}
