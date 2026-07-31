package io.github.loredock.agent.model.result;

import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;

/** @param snapshot 新建或并发复用的运行快照 @param newlyAccepted 本事务是否首次写入运行与首事件 */
public record AgentRunAcceptanceResult(AgentRunSnapshot snapshot, boolean newlyAccepted) {
}
