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
     * @param jobId 任务 ID
     * @return 当前任务快照，不存在时为空
     */
    Optional<JobSnapshot> find(UUID jobId);

    /**
     * 幂等取消正在运行的任务；尚未开始、已经终结或不存在时不改变状态。
     *
     * @param jobId 任务 ID
     */
    void cancel(UUID jobId);
}
