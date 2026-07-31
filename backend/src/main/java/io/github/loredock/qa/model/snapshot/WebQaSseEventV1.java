package io.github.loredock.qa.model.snapshot;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentResultType;
import java.time.Instant;

/**
 * SSE v1 有限数据体；未使用字段为空，永不包含原始 Agent payload、模型名、提示或证据正文。
 */
public record WebQaSseEventV1(
        String version,
        long sequence,
        Instant occurredAt,
        String phase,
        String tool,
        Integer count,
        String textDelta,
        AgentResultType resultType,
        AgentErrorCode errorCode
) {
}
