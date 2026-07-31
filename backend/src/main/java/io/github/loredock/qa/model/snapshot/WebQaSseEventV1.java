package io.github.loredock.qa.model.snapshot;

import io.github.loredock.agent.api.AgentRun;
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
        AgentRun.ResultType resultType,
        AgentRun.ErrorCode errorCode
) {
}
