package io.github.loredock.agent.config;

import java.time.Duration;

/** 启动时固定的服务端硬上限。 */
public record AgentRuntimeLimits(
        int maxSteps,
        int maxModelCalls,
        Duration timeout,
        int maxResultsPerTool,
        int maxSnippetCharacters,
        int maxContextCharacters,
        int maxAnswerCharacters,
        int maxEvents
) {
}
