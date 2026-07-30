package io.github.loredock.agent.application;

/** 实际步骤、模型调用和可选 Token 用量。 */
public record AgentExecutionUsage(
        int stepCount,
        int modelCallCount,
        int retrievalCount,
        int trimmedCharacterCount,
        Long inputTokens,
        Long outputTokens,
        long elapsedMillis
) {
    /** 未开始执行时使用的零计数；Token 仍为未知。 */
    public static AgentExecutionUsage none() {
        return new AgentExecutionUsage(0, 0, 0, 0, null, null, 0);
    }
}
