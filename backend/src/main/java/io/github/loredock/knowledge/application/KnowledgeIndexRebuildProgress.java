package io.github.loredock.knowledge.application;

/** generation 重建向后台任务报告单调进度与存活心跳的端口。 */
public interface KnowledgeIndexRebuildProgress {

    /** @param percentage 0 到 100 的单调进度。 */
    void update(int percentage);

    /** 刷新长事务期间的任务心跳。 */
    void heartbeat();
}
