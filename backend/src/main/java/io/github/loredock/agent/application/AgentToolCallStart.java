package io.github.loredock.agent.application;

import java.util.UUID;

/** 已提交的工具调用起始事实。 */
public record AgentToolCallStart(UUID callId, int sequence) {
}
