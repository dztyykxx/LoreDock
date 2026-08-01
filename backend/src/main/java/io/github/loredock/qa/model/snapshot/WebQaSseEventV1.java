package io.github.loredock.qa.model.snapshot;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRun;
import java.time.Instant;
import java.util.List;

/**
 * SSE v1 有限数据体；未使用字段为空，永不包含原始 Agent payload、模型名、提示或证据正文。
 */
public record WebQaSseEventV1(
        String version,
        long sequence,
        Instant occurredAt,
        AgentEvent.Type eventType,
        AgentEvent.SubjectType subjectType,
        String phase,
        String tool,
        String purpose,
        String parameterSummary,
        String resultSummary,
        Integer count,
        Long durationMillis,
        String status,
        List<AgentEvent.Source> sources,
        String summary,
        boolean modelGenerated,
        boolean truncated,
        String textDelta,
        AgentRun.ResultType resultType,
        AgentRun.ErrorCode errorCode
) {
    public WebQaSseEventV1 {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
