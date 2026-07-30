package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEventType;

import java.time.Instant;
import java.util.UUID;

/** 单条已提交的公开事件；payload 只能保存允许公开的摘要或最终文本增量。 */
public record AgentEventSnapshot(
        UUID eventId,
        UUID runId,
        long sequence,
        AgentEventType type,
        String payload,
        Instant createdAt
) {
}
