package io.github.loredock.agent.model.snapshot;

import io.github.loredock.agent.model.enums.AgentEventType;
import java.time.Instant;

/** 单条已提交的公开事件；payload 只能保存类型化白名单字段和有界公开增量。 */
public record AgentEventSnapshot(
        Long eventId,
        Long runId,
        long sequence,
        AgentEventType type,
        String subjectType,
        String payload,
        Instant createdAt
) {
    /** 保留旧测试和单字符串事件构造兼容。 */
    public AgentEventSnapshot(
            Long eventId, Long runId, long sequence, AgentEventType type, String payload, Instant createdAt
    ) {
        this(eventId, runId, sequence, type, legacySubject(type), payload, createdAt);
    }

    private static String legacySubject(AgentEventType type) {
        return switch (type) {
            case MODEL_STARTED, MODEL_STAGE -> "MODEL";
            case SOURCE_FOUND, SOURCE_DISCOVERED, TOOL_STARTED, TOOL_COMPLETED -> "TOOL";
            case CITATION_VALIDATION -> "VALIDATOR";
            default -> "AGENT";
        };
    }
}
