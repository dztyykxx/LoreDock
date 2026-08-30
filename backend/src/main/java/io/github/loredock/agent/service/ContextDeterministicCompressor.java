package io.github.loredock.agent.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 确定性压缩（设计文档 §6.1）：只处理组装结果中的角色化历史轮次区间，
 * 按完整 User/Assistant 轮次从旧到新整轮丢弃，半轮不截断。
 *
 * <p>组装结果的上下文块（当前任务、人工决定、事实引用、阶段标记）一律不删改，
 * 正文与 Tool 原文在设计上不进入组装消息（由稳定引用 + 受限业务 Tool 承担），
 * 因此确定性压缩结果可复现：同一输入两次压缩输出完全一致。</p>
 */
public class ContextDeterministicCompressor {

    /** 压缩结果：被裁减后的消息、丢弃的历史轮次数与节省的估算 token。 */
    public record TrimResult(
            List<Message> messages,
            int droppedHistoryTurns,
            int trimmedTokens,
            int remainingHistoryTurns
    ) {
    }

    private final ContextTokenEstimator estimator;

    public ContextDeterministicCompressor(ContextTokenEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * 把消息序列 [historyStart, historyEnd) 之间的历史轮次整轮裁减，使历史估算不超过
     * {@code allowedHistoryTokens}；区间之外的上下文块一律不动，半轮（单条孤立 User/Assistant）绝不截断。
     *
     * @param messages 完整消息序列
     * @param historyStart 历史轮次区间起点（含）
     * @param historyEnd 历史轮次区间终点（不含）
     * @param allowedHistoryTokens 历史轮次允许占用的估算 token 上限
     */
    public TrimResult trim(List<Message> messages, int historyStart, int historyEnd, int allowedHistoryTokens) {
        List<Message> head = new ArrayList<>(messages.subList(0, historyStart));
        List<Message> history = new ArrayList<>(messages.subList(historyStart, historyEnd));
        List<Message> tail = new ArrayList<>(messages.subList(historyEnd, messages.size()));
        int dropped = 0;
        int beforeHistory = estimator.estimate(history).tokens();
        while (history.size() >= 2 && estimator.estimate(history).tokens() > allowedHistoryTokens) {
            // 只从最旧整轮（第 0、1 条）删除；剩余单条（半轮）不删除。
            history.subList(0, 2).clear();
            dropped++;
        }
        List<Message> result = new ArrayList<>(head);
        result.addAll(history);
        result.addAll(tail);
        int before = estimator.estimate(messages).tokens();
        int after = estimator.estimate(result).tokens();
        return new TrimResult(result, dropped, before - after, history.size() / 2);
    }
}
