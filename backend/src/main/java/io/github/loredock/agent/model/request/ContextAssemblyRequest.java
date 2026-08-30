package io.github.loredock.agent.model.request;

import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.model.context.ConversationContext;
import io.github.loredock.agent.model.context.WorkflowContext;
import io.github.loredock.agent.model.enums.AgentNode;
import io.github.loredock.agent.model.enums.ContextPurpose;

/**
 * 上下文组装请求（设计文档 §4.1）：输入模型禁止接收父图原始 messages、
 * 完整 Tool 回执或旧 Agent JSON。
 *
 * <p>注意：系统规则不在此重复携带——框架在 Agent 子图入口已注入
 * {@code instruction}（AgentInstructionMessage），组装层只追加本节点的任务语义块。</p>
 *
 * @param conversationId 会话标识（跨轮历史读取与摘要缓存的统一次元）
 * @param runId 当前 run
 * @param agentNode 当前 Agent 节点
 * @param purpose 组装意图
 * @param currentInstruction 本轮用户指令（来自 currentInstruction state 键）
 * @param conversation 会话上下文
 * @param workflow 当前轮工作上下文
 * @param budget 预算配置
 */
public record ContextAssemblyRequest(
        Long conversationId,
        Long runId,
        AgentNode agentNode,
        ContextPurpose purpose,
        String currentInstruction,
        ConversationContext conversation,
        WorkflowContext workflow,
        ContextBudget budget
) {
}
