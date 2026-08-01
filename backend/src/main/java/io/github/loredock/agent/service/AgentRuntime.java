package io.github.loredock.agent.service;

import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import java.util.Objects;
import java.util.function.Consumer;

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

    /**
     * 执行 Agent 并观察尚未完成最终校验的公开回答增量。默认实现保持测试替身兼容，真实流式实现应尽早回调。
     *
     * @param request 已固定范围、定义和资源上限的运行请求
     * @param answerDeltaObserver 只接收用户可见回答正文，不接收结构化 JSON 或隐藏消息
     * @return 需要由业务服务继续校验证据与引用的结构化结果
     */
    default AgentExecutionResult execute(
            AgentExecutionRequest request,
            Consumer<String> answerDeltaObserver
    ) {
        Objects.requireNonNull(answerDeltaObserver, "answer delta observer");
        AgentExecutionResult result = execute(request);
        if (result.modelResult().resultType()
                == io.github.loredock.agent.model.enums.AgentResultType.ANSWER) {
            answerDeltaObserver.accept(result.modelResult().text());
        }
        return result;
    }
}
