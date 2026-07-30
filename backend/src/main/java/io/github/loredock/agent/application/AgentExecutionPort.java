package io.github.loredock.agent.application;

/** 隐藏 Spring AI、具体模型 SDK 和 ReactAgent 类型的运行边界。 */
public interface AgentExecutionPort {

    /**
     * 同步执行单次独立 Agent；实现可以流式通知持久化事件，但不得跨运行共享记忆。
     *
     * @param request 固定版本、范围、限制和短生命周期问题
     * @param observer 公开阶段与工具摘要观察器
     * @return 尚需服务端引用校验的结构化模型结果
     */
    AgentExecutionResult execute(AgentExecutionRequest request, AgentExecutionObserver observer);
}
