package io.github.loredock.agent.application;

/** 专用 Agent 执行器中处理一个已提交运行的端口。 */
@FunctionalInterface
public interface AgentRunTaskExecutor {

    /** @param request 已持久化且固定范围的执行请求 */
    void execute(AgentExecutionRequest request);
}
