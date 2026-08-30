package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.agent.exception.ContextLimitExceededException;
import io.github.loredock.agent.exception.ContextRunBudgetExceededException;
import io.github.loredock.agent.model.context.ContextBudget;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 预算守卫 Hook 单元测试：放行/计费、最旧闭合 Tool 组裁剪、最新未闭合组保护与两种受控异常。
 */
class ContextBudgetGuardHookTest {

    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    /** 业务目的：估算在输入上限内且 run 累计未耗尽时守卫放行，并按本次估算计入 run 累计。 */
    @Test
    void allowsWhenUnderBudgetAndChargesRunInput() {
        ContextBudget budget = new ContextBudget(128000, 96000, 24000, 8000, 72000, 64000, 512000, 1, 3);
        AtomicLong spent = new AtomicLong();
        ContextBudgetGuardHook hook = new ContextBudgetGuardHook(budget, estimator, spent, 1L, 10L);
        List<Message> messages = List.of(new UserMessage("你好，帮我看看上轮结论"));

        List<Message> result = hook.guardedMessages(messages, "main_agent");

        assertThat(result).hasSize(1);
        assertThat(spent.get()).isEqualTo(estimator.estimate(messages).tokens());
        System.out.printf("测试证据：场景=守卫放行，run 累计=%d（等于估算%d）%n", spent.get(),
                estimator.estimate(messages).tokens());
    }

    /** 业务目的：超单次上限时只清理最旧且已闭合的 Tool Call/Result 组，最新未闭合组保留。 */
    @Test
    void trimsOldestClosedToolGroupButKeepsLatestUnclosed() {
        // 第 1 组闭合（t1 有响应），第 2 组未闭合（t2 无响应）：预算只允许保留一对，必须删旧留新。
        AtomicLong spent = new AtomicLong();
        List<Message> messages = List.of(
                user("任务消息" + "长".repeat(200)),
                assistantWithTool("t1"),
                toolResponse("t1", "结果" + "长".repeat(300)),
                assistantWithTool("t2"));
        int full = estimator.estimate(messages).tokens();
        int afterTrimOfGroup1 = estimator.estimate(List.of(messages.get(0), messages.get(3))).tokens();
        // 精确预算：完整链超限，但删除第一组后放行。
        ContextBudget budget = new ContextBudget(128000, afterTrimOfGroup1 + 1, 100, 50, 72000, 64000, 512000, 1, 3);
        ContextBudgetGuardHook hook = new ContextBudgetGuardHook(budget, estimator, spent, 1L, 10L);

        List<Message> result = hook.guardedMessages(messages, "retriever");

        assertThat(full).isGreaterThan(afterTrimOfGroup1);
        assertThat(result.stream().noneMatch(m -> m instanceof ToolResponseMessage)).isTrue();
        assertThat(result).hasSize(2); // 只保留任务消息与最新未闭合 Tool Call
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        System.out.printf("测试证据：场景=守卫裁剪，完整估算=%d，裁剪后=%d，仅保留未闭合组=%s%n",
                full, estimator.estimate(result).tokens(), result.get(1) instanceof AssistantMessage);
    }

    /** 业务目的：所有闭合组都被清理后仍超限时不得发送模型请求（单次上限异常）。 */
    @Test
    void throwsWhenStillOverLimitAfterTrimmingClosedGroups() {
        AtomicLong spent = new AtomicLong();
        ContextBudget budget = new ContextBudget(128000, 5, 100, 50, 72000, 64000, 512000, 1, 3);
        ContextBudgetGuardHook hook = new ContextBudgetGuardHook(budget, estimator, spent, 1L, 10L);

        assertThatThrownBy(() -> hook.guardedMessages(
                List.of(new UserMessage("不可能裁剪到 10"), new UserMessage("还是超")), "drafter"))
                .isInstanceOf(ContextLimitExceededException.class);
        System.out.printf("测试证据：场景=守卫超限拒绝，抛出 ContextLimitExceededException，run 累计=%d%n", spent.get());
    }

    /** 业务目的：run 累计输入预算耗尽时直接中断（异常），不通过继续裁剪换取调用。 */
    @Test
    void runBudgetExhaustionThrowsWithoutContinuedTrimming() {
        ContextBudget budget = new ContextBudget(128000, 96000, 24000, 8000, 72000, 64000, 0, 1, 3);
        AtomicLong spent = new AtomicLong();
        ContextBudgetGuardHook hook = new ContextBudgetGuardHook(budget, estimator, spent, 1L, 10L);

        assertThatThrownBy(() -> hook.guardedMessages(List.of(new UserMessage("任一消息")), "reviewer"))
                .isInstanceOf(ContextRunBudgetExceededException.class);
        System.out.printf("测试证据：场景=run 累计耗尽即中断，spent=%d（0 上限）%n", spent.get());
    }

    private static UserMessage user(String text) {
        return new UserMessage(text);
    }

    private static AssistantMessage assistantWithTool(String id) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "knowledge_search", "{}")))
                .build();
    }

    private static ToolResponseMessage toolResponse(String id, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "knowledge_search", data)))
                .build();
    }
}
