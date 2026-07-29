package io.github.loredock.job.domain;

/**
 * 后台任务持久状态；终态不可再次转换。
 */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /**
     * @return 当前状态是否为不可变终态
     */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
