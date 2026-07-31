package io.github.loredock.qa.model.response;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import java.time.Instant;

/** 经服务端持久化的公开用户或助手消息。 */
public record WebQaMessageResponse(
        Long id,
        WebQaMessageRole role,
        String content,
        AgentRun.ResultType resultType,
        AgentRun.RefusalReason refusalReason,
        Instant createdAt
) {
}
