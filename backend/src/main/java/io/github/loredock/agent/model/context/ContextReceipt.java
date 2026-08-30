package io.github.loredock.agent.model.context;

import io.github.loredock.agent.model.enums.AgentNode;
import io.github.loredock.agent.model.enums.ContextMode;
import io.github.loredock.agent.model.enums.ContextPurpose;
import java.util.List;

/**
 * 组装与预算守卫的运行观测收货单（设计文档 §4.2）：只含统计与策略说明，
 * 不含消息正文，保证日志脱敏。
 */
public record ContextReceipt(
        AgentNode agentNode,
        ContextPurpose purpose,
        ContextMode mode,
        String estimateMode,
        int estimatedInputTokens,
        int inputUtf8Bytes,
        int beforeCompressionTokens,
        int trimmedTokens,
        int compressedTokens,
        int droppedHistoryTurns,
        int replacedBodiesWithReferences,
        List<String> appliedPolicies
) {
    public ContextReceipt {
        appliedPolicies = appliedPolicies == null ? List.of() : List.copyOf(appliedPolicies);
    }
}
