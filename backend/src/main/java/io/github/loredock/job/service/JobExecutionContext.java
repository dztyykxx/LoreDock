package io.github.loredock.job.service;


/**
 * 受控任务处理器更新进度和心跳的最小上下文，不暴露线程池或数据库管理能力。
 */
public interface JobExecutionContext {

    /** @return 当前任务 ID。 */
    Long jobId();

    /** @return 可选输入对象键。 */
    String inputObjectKey();

    /** @return 可选项目范围；无范围的既有任务返回 null。 */
    default Long projectId() {
        return null;
    }

    /** @return 可选分支范围；无范围的既有任务返回 null。 */
    default Long branchId() {
        return null;
    }

    /** @return 可选代码快照范围；无范围的既有任务返回 null。 */
    default Long snapshotId() {
        return null;
    }

    /**
     * 单调更新 0 到 99 的执行进度；成功终结时由平台写入 100。
     *
     * @param progress 新进度
     */
    void updateProgress(int progress);

    /** 刷新当前任务心跳。 */
    void heartbeat();
}
