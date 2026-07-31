package io.github.loredock.qa.model.result;

import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import java.time.Instant;

/** 问答消息仓储边界内的用户原问题或经运行时校验的终态公开消息。 */
public record WebQaMessageRecord(
        Long id,
        Long questionId,
        WebQaMessageRole role,
        String content,
        AgentResultType resultType,
        AgentRefusalReason refusalReason,
        Instant createdAt
) {
}
