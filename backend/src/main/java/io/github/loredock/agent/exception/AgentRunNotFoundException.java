package io.github.loredock.agent.exception;

/** 运行不存在或当前操作者无权读取时统一抛出，避免泄露运行是否存在。 */
public final class AgentRunNotFoundException extends RuntimeException {
    public AgentRunNotFoundException() {
        super("agent run not found");
    }
}
