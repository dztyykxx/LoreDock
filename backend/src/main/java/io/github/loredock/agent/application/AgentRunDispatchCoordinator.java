package io.github.loredock.agent.application;

/** 在运行接受事实所在的最外层事务提交后安排 Agent 执行。 */
public interface AgentRunDispatchCoordinator {

    /**
     * 没有活动事务时立即调度；存在活动事务时只在提交成功后调度一次。
     *
     * @param request 只在进程内短暂持有的执行请求
     */
    void dispatchAfterCommit(AgentExecutionRequest request);
}
