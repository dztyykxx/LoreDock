package io.github.loredock.agent.model.request;

import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import java.time.Instant;
import java.util.List;

/** 交给模型适配器的当前运行固定输入；问题不进入持久化端口。 */
public record AgentExecutionRequest(
        Long runId,
        String question,
        String skillMarkdown,
        String outputSchema,
        AgentScopeSnapshot scope,
        AgentVersionSnapshot versions,
        AgentRuntimeLimits limits,
        Instant deadline,
        List<AgentService.ConversationMessage> conversationHistory
) {
    public AgentExecutionRequest {
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    /** 保留独立单轮运行构造兼容。 */
    public AgentExecutionRequest(
            Long runId,
            String question,
            String skillMarkdown,
            String outputSchema,
            AgentScopeSnapshot scope,
            AgentVersionSnapshot versions,
            AgentRuntimeLimits limits,
            Instant deadline
    ) {
        this(runId, question, skillMarkdown, outputSchema, scope, versions, limits, deadline, List.of());
    }
}
