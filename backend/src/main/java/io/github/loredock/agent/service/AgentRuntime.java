package io.github.loredock.agent.service;

import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentExecutionResult;

/**
 * Agent 运行时稳定边界，隔离具体 Agent 框架并允许业务测试使用轻量替身。
 */
public interface AgentRuntime {

    /**
     * 同步执行一次独立 Agent 运行；实现可通过观察器发布有限的公开阶段事件。
     *
     * @param request 已固定业务范围、定义和资源上限的运行请求
     * @return 需要由业务服务继续校验证据与引用的结构化结果
     */
    AgentExecutionResult execute(AgentExecutionRequest request);
}
