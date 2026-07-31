package io.github.loredock.agent.model.result;

import java.util.List;

/** 模型执行结果、实际用量和运行内证据；仍需可信结果校验。 */
public record AgentExecutionResult(
        ProjectQaModelResult modelResult,
        List<AgentEvidence> evidence,
        AgentExecutionUsage usage
) {
    public AgentExecutionResult {
        evidence = List.copyOf(evidence);
    }
}
