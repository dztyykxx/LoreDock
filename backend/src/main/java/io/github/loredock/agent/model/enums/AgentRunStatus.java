package io.github.loredock.agent.model.enums;

/**
 * Agent 运行的单调生命周期状态。
 *
 * <p>与 {@code KnowledgeTaskService.RunStatus} 保持枚举全集一致：除终态外，
 * {@code PAUSE_REQUESTED} 与 {@code WAITING_FOR_USER} 是协作式中途状态——前者表示已请求在
 * 下一个安全边界暂停（不打断正在进行的操作），后者表示运行已提交且可读取 Checkpoint、
 * 等待人工指导继续同一 run，两者都不是不可再改变的终态。</p>
 */
public enum AgentRunStatus {
    ACCEPTED,
    RUNNING,
    /**
     * 已请求在安全边界暂停（由管理员发起）；协作式暂停使当前操作自然结束，而非中断。
     */
    PAUSE_REQUESTED,
    /**
     * 已提交可读取的 Checkpoint 并等待人工指导：可能是重试耗尽、上下文超出预算或显式暂停
     * 之后的恢复点，管理员追加指导后通过 resume 接口继续同一 run。
     */
    WAITING_FOR_USER,
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
