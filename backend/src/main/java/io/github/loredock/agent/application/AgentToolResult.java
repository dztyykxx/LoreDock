package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEvidence;

import java.util.List;

/** 单次工具调用返回给模型的有界上下文和同时登记的来源元数据。 */
public record AgentToolResult(
        String modelContext,
        List<AgentEvidence> evidence,
        int resultCount,
        int trimmedCharacterCount
) {
    public AgentToolResult {
        evidence = List.copyOf(evidence);
    }
}
