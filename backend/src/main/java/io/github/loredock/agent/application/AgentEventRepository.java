package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 持久化公开事件的端口，序号由数据库中当前运行的既有事件单调分配。 */
public interface AgentEventRepository {

    /** @return 提交后可供下游读取的事件 */
    AgentEventSnapshot append(UUID runId, AgentEventType type, String safePayload, Instant createdAt);

    /** @return 严格大于 afterSequence 且不超过 limit 的事件 */
    List<AgentEventSnapshot> findAfter(UUID runId, long afterSequence, int limit);

    /** @return 当前运行已提交的最后事件序号；尚无事件时为零 */
    long lastSequence(UUID runId);
}
