package io.github.loredock.qa.model.response;

import io.github.loredock.agent.api.AgentEvent;
import java.time.Instant;

/** 刷新后恢复处理过程的单条安全公开事件。 */
public record WebQaProcessEventResponse(
        long sequence,
        AgentEvent.Type type,
        AgentEvent.SubjectType subjectType,
        AgentEvent.Payload payload,
        Instant occurredAt
) {
}
