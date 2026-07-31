package io.github.loredock.agent.model.command;

import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import java.time.Instant;

/** 首次持久化运行所需的完整但已脱敏数据。 */
public record AgentRunCreateData(
        Long runId,
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
