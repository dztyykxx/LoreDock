package io.github.loredock.agent.model.enums;

/** Agent 运行的单调生命周期状态。 */
public enum AgentRunStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    TERMINATED;

    /** @return 当前状态是否已经不可再改变 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == TERMINATED;
    }
}
