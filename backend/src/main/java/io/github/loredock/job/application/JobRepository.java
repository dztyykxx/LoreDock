package io.github.loredock.job.application;

import io.github.loredock.job.domain.BackgroundJob;
import io.github.loredock.job.domain.JobStatus;
import io.github.loredock.platform.audit.AuditMetadata;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 后台任务持久化端口；状态更新使用期望状态条件，防止并发终结相互覆盖。
 */
public interface JobRepository {

    /** 保存提交后、执行前的 PENDING 任务。 */
    void insertPending(BackgroundJob job, AuditMetadata audit);

    /** @return 指定任务的当前领域状态。 */
    Optional<BackgroundJob> find(UUID jobId);

    /** @return 指定任务类型最早创建的活动任务，终态任务不返回。 */
    Optional<BackgroundJob> findActiveByType(String type);

    /**
     * 条件更新任务状态和审计字段。
     *
     * @return 当前数据库状态等于 expectedStatus 且更新成功时为 true
     */
    boolean update(BackgroundJob job, JobStatus expectedStatus, Instant updatedAt, String updatedBy);

    /**
     * 将失去心跳的 RUNNING 任务批量终结为失败，不执行自动重放。
     *
     * @return 恢复的陈旧任务数量
     */
    int failStaleRunning(Instant heartbeatBefore, Instant recoveredAt, String updatedBy);
}
