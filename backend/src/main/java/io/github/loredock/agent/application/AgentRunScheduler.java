package io.github.loredock.agent.application;

/** 事务提交后把已落库运行安排到专用有界执行器。 */
public interface AgentRunScheduler {

    /**
     * @param request 包含短生命周期问题的执行请求
     * @return 已进入执行队列时为 true；队列满时为 false
     */
    boolean schedule(AgentExecutionRequest request);
}
