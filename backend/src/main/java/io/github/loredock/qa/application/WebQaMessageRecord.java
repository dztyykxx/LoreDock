package io.github.loredock.qa.application;

import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.qa.domain.WebQaMessageRole;

import java.time.Instant;
import java.util.UUID;

/** 问答消息仓储边界内的用户原问题或经运行时校验的终态公开消息。 */
public record WebQaMessageRecord(
        UUID id,
        UUID questionId,
        WebQaMessageRole role,
        String content,
        AgentResultType resultType,
        AgentRefusalReason refusalReason,
        Instant createdAt
) {
}
