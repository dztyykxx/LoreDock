package io.github.loredock.agent.model.context;

import java.util.List;

/**
 * 会话上下文（跨轮输入，设计文档 §4.1）：组装时只投影必要的会话级事实，
 * 不携带旧轮 Tool 链或专家原始 JSON。
 */
public record ConversationContext(
        String originalGoal,
        List<DialogueTurn> recentTurns,
        List<ConfirmedDecision> confirmedDecisions,
        AdministratorGuidance pendingAdministratorGuidance,
        boolean historyTruncated
) {

    /** 单轮角色化对话：role 取 USER / ASSISTANT，对应历史重建后的角色化轮次。 */
    public record DialogueTurn(String role, String text) {
    }

    /** 已确认人工决定（含来源编号，供状态引用投影）。 */
    public record ConfirmedDecision(String id, String summary) {
    }

    /** 待应用的管理员追加指导：只对其恢复目标节点直接注入，该节点成功后清除。 */
    public record AdministratorGuidance(String targetAgent, String text) {
    }

    public ConversationContext {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        confirmedDecisions = confirmedDecisions == null ? List.of() : List.copyOf(confirmedDecisions);
    }
}
