package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;

import java.time.Instant;
import java.util.UUID;

/** 交给模型适配器的当前运行固定输入；问题不进入持久化端口。 */
public record AgentExecutionRequest(
        UUID runId,
        String question,
        String skillMarkdown,
        String outputSchema,
        AgentScopeSnapshot scope,
        AgentVersionSnapshot versions,
        AgentRuntimeLimits limits,
        Instant deadline
) {
}
