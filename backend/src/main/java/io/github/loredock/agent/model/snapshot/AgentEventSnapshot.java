package io.github.loredock.agent.model.snapshot;

import io.github.loredock.agent.model.enums.AgentEventType;
import java.time.Instant;

/** 单条已提交的公开事件；payload 只能保存允许公开的状态、数量或错误摘要，不能保存正文。 */
public record AgentEventSnapshot(
        Long eventId,
        Long runId,
        long sequence,
        AgentEventType type,
        String payload,
        Instant createdAt
) {
}
