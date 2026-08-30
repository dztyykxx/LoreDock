package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.exception.ContextLimitExceededException;
import io.github.loredock.agent.exception.ContextRunBudgetExceededException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 每次 ReAct 模型调用前的预算守卫（设计文档 §6 / §9）：估算并记录当前消息链，
 * 超单次输入上限时只清理最旧且已闭合的 Tool Call/Result 组，最新未闭合配对与当前任务消息受保护；
 * 清理后仍超限抛出 {@link ContextLimitExceededException}，run 累计预算耗尽抛出
 * {@link ContextRunBudgetExceededException}，均不发送模型请求。
 *
 * <p>注：使用框架 {@code MessagesModelHook.BEFORE_MODEL} 位置（每次循环模型调用前执行），
 * 不重写 {@code StreamingModelInterceptor}；Hook 内部不访问数据库、不发起 LLM 压缩，
 * 以免破坏当前 Tool 链连续性或形成递归模型调用。</p>
 */
public class ContextBudgetGuardHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetGuardHook.class);

    private final ContextBudget budget;
    private final ContextTokenEstimator estimator;
    private final AtomicLong runInputSpent;
    private final Long runId;
    private final Long conversationId;
    /** 每个 Agent 在该 run 内的模型调用序号（before-call 递增，completed 读取同号）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> seqByAgent =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 每个 Agent 最近一次发送前的估算 token（供 completed 行对齐估算与实际）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> lastEstimateByAgent =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param budget 预算配置
     * @param estimator Token 估算器
     * @param runInputSpent 该 run 已累计发送的输入估算 token（全部 Agent 共享一个累加器）
     */
    public ContextBudgetGuardHook(
            ContextBudget budget, ContextTokenEstimator estimator, AtomicLong runInputSpent,
            Long runId, Long conversationId
    ) {
        this.budget = budget;
        this.estimator = estimator;
        this.runInputSpent = runInputSpent;
        this.runId = runId;
        this.conversationId = conversationId;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        String agent = agentName(config);
        List<Message> messages = guardedMessages(previousMessages == null ? List.of() : previousMessages, agent);
        return new AgentCommand(messages, UpdatePolicy.REPLACE);
    }

    /** @return 预算守卫后的消息列表（可能被裁剪）；超限或 run 累计耗尽时抛出受控异常（供单元测试直接调用）。 */
    List<Message> guardedMessages(List<Message> messages, String agent) {
        int callSeq = nextSeq(agent);
        ContextTokenEstimator.Estimate estimate = estimator.estimate(messages);
        ContextTokenEstimator.Estimate before = estimate;
        if (estimate.tokens() > budget.maxInputTokens()) {
            List<Message> trimmed = trimClosedToolGroups(messages);
            ContextTokenEstimator.Estimate after = estimator.estimate(trimmed);
            if (after.tokens() > budget.maxInputTokens()) {
                throw new ContextLimitExceededException(
                        "节点内消息链估算超输入硬上限且无法保守裁剪：agent=" + agent
                                + " 估算=" + after.tokens() + " 上限=" + budget.maxInputTokens()
                                + " conversationId=" + conversationId + " runId=" + runId);
            }
            messages = trimmed;
            estimate = after;
        }
        lastEstimateByAgent.put(agent, estimate.tokens());
        long spentAfter = runInputSpent.addAndGet(estimate.tokens());
        if (spentAfter > budget.maxRunInputTokens()) {
            throw new ContextRunBudgetExceededException(
                    "run 累计输入预算耗尽：agent=" + agent + " 累计=" + spentAfter
                            + " 上限=" + budget.maxRunInputTokens()
                            + " conversationId=" + conversationId + " runId=" + runId);
        }
        log.info("agent_model_context_checked runId={} agent={} callSeq={} estimateMode={} "
                        + "estimatedInputTokens={} inputUtf8Bytes={} beforeTokens={} trimmedTokens={} runSpentTokens={} runSpentLimit={}",
                runId, agent, callSeq, estimate.mode(), estimate.tokens(), estimate.utf8Bytes(),
                before.tokens(), before.tokens() - estimate.tokens(), spentAfter, budget.maxRunInputTokens());
        return messages;
    }

    /** @return 该 Agent 本次（已完成守卫放行的最后一次）发送前估算 token，供 completed 行对齐。 */
    int lastEstimatedTokens(String agent) {
        return lastEstimateByAgent.getOrDefault(agent, -1);
    }

    /** @return 该 Agent 最近一次校验通过的调用序号（before-call 写、completed 读同一序号）。 */
    int lastCallSeq(String agent) {
        return seqByAgent.getOrDefault(agent, new java.util.concurrent.atomic.AtomicInteger()).get();
    }

    private int nextSeq(String agent) {
        return seqByAgent.computeIfAbsent(agent, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
    }

    /** @return 去除最旧且已闭合的 Tool Call/Result 组后的消息；最新未闭合组与最新任务消息绝不删除。 */
    List<Message> trimClosedToolGroups(List<Message> messages) {
        List<Message> copy = new ArrayList<>(messages);
        boolean changed = true;
        while (changed && estimator.estimate(copy).tokens() > budget.maxInputTokens()) {
            changed = false;
            for (int i = 0; i < copy.size(); i++) {
                Message candidate = copy.get(i);
                if (!(candidate instanceof AssistantMessage assistant) || !assistant.hasToolCalls()) {
                    continue;
                }
                Set<String> requested = new HashSet<>();
                assistant.getToolCalls().forEach(toolCall -> requested.add(toolCall.id()));
                Set<String> executed = new HashSet<>();
                int j = i + 1;
                // 收集从 i 起连续的工具响应，直到覆盖全部请求的 toolCallId 或遇到非响应消息。
                while (j < copy.size() && copy.get(j) instanceof ToolResponseMessage response) {
                    response.getResponses().forEach(item -> executed.add(item.id()));
                    j++;
                    if (executed.containsAll(requested)) {
                        break;
                    }
                }
                if (executed.containsAll(requested)) {
                    // 只删除最旧一组（该组与其响应），其后内容保持连续，最新未闭合组不受影响。
                    copy.subList(i, j).clear();
                    changed = true;
                    break;
                }
            }
        }
        return copy;
    }

    /** @return Hook 名称（Hook 集合内的唯一标识）。 */
    @Override
    public String getName() {
        return "knowledgeContextBudgetGuard";
    }

    /**
     * @return 只在模型调用前执行（BEFORE_MODEL）。
     *         框架默认只识别 AgentHook/ModelHook 接口类型，MessagesModelHook 子类必须显式声明位置。
     */
    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_MODEL};
    }

    /** @return 当前调用所属 Agent 节点名（{@code _AGENT_} 元数据，去掉 subgraph_ 前缀）。 */
    private static String agentName(RunnableConfig config) {
        Object value = config == null ? null
                : config.metadata("_AGENT_").orElse(null);
        String agent = value == null ? "unknown" : String.valueOf(value);
        return agent.startsWith("subgraph_") ? agent.substring("subgraph_".length()) : agent;
    }
}
