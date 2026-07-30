package io.github.loredock.job.application;

import io.github.loredock.job.domain.JobSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * 后台任务提交和查询端口。提交默认非幂等，查询和取消保持幂等。
 */
public interface BackgroundJobService {

    /**
     * 先持久化 PENDING 记录，再提交到受控执行器。
     *
     * @param request 任务命令
     * @return 新任务 ID
     */
    UUID submit(JobRequest request);

    /**
     * 在单实例进程内复用同类型 PENDING/RUNNING 任务；终态后创建新任务。
     *
     * @param request 任务命令
     * @return 既有活动任务或新任务 ID
     */
    UUID submitSingleFlight(JobRequest request);

    /**
     * 以数据库部分唯一约束提交同分支排他任务。该操作不复用既有任务，冲突稳定映射为
     * {@code CODE_SNAPSHOT_JOB_ACTIVE}；项目、分支和快照范围都必须提供。
     *
     * @param request 带完整代码范围的构建或重建命令
     * @return 新任务 ID
     */
    UUID submitExclusiveByBranch(JobRequest request);

    /**
     * @param jobId 任务 ID
     * @return 当前任务快照，不存在时为空
     */
    Optional<JobSnapshot> find(UUID jobId);

    /** @return 指定类型最早创建的 PENDING/RUNNING 任务。 */
    Optional<JobSnapshot> findActiveByType(String type);

    /**
     * 幂等取消正在运行的任务；尚未开始、已经终结或不存在时不改变状态。
     *
     * @param jobId 任务 ID
     */
    void cancel(UUID jobId);
}
