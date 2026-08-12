package io.github.loredock.agent.model.enums;

/** Agent 运行的单调生命周期状态。 */
public enum AgentRunStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    TERMINATED,
    /**
     * 管理员主动停止的知识整理运行终态；
     * 与知识任务 API 的 CANCELLED 投影一致，已提交的工作修订保留，后续消息创建新 run。
     */
    CANCELLED;

    /** @return 当前状态是否已经不可再改变 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == TERMINATED || this == CANCELLED;
    }
}
