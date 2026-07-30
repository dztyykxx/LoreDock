package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEvidence;
import io.github.loredock.agent.domain.ProjectQaModelResult;

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
