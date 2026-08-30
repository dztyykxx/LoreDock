package io.github.loredock.agent.model.context;

/**
 * 上下文预算（设计文档 §3 配置表）；由 {@code AgentContextProperties} 在启动时填充，
 * 所有阈值均为单次模型调用与单 run 的硬边界，不绑定模型名称。
 */
public record ContextBudget(
        int maxWindowTokens,
        int maxInputTokens,
        int outputReserveTokens,
        int safetyReserveTokens,
        int compressionTriggerTokens,
        int compressionTargetTokens,
        long maxRunInputTokens,
        int maxLlmCompressionCalls,
        int maxRollingSummaryGenerations
) {

    /** 允许值语义校验：阈值间大小关系与正数要求，违反时抛 IllegalStateException。 */
    public void validateInvariants() {
        if (compressionTargetTokens <= 0 || compressionTriggerTokens <= 0 || maxInputTokens <= 0
                || maxWindowTokens <= 0 || outputReserveTokens < 0 || safetyReserveTokens < 0
                || maxRunInputTokens <= 0 || maxLlmCompressionCalls < 0 || maxRollingSummaryGenerations <= 0
                || compressionTargetTokens >= compressionTriggerTokens
                || compressionTriggerTokens >= maxInputTokens
                || (long) maxInputTokens + outputReserveTokens + safetyReserveTokens > maxWindowTokens) {
            throw new IllegalStateException("知识整理上下文预算配置不合法，请检查 loredock.agent.context 阈值："
                    + this);
        }
    }
}
