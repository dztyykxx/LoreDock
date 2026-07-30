package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.qa.domain.WebQaMessageRole;

import java.time.Instant;
import java.util.UUID;

/** 经服务端持久化的公开用户或助手消息。 */
public record WebQaMessageResponse(
        UUID id,
        WebQaMessageRole role,
        String content,
        AgentResultType resultType,
        AgentRefusalReason refusalReason,
        Instant createdAt
) {
}
