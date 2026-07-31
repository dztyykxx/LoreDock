package io.github.loredock.job.model;

import io.github.loredock.job.exception.InvalidJobTransitionException;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.job.model.snapshot.JobSnapshot;
import java.time.Instant;
import java.util.Objects;

/**
 * 集中维护后台任务状态机和进度规则，避免仓储、线程池和具体处理器各自复制转换判断。
 */
public final class BackgroundJob {

    private final Long id;
    private final String type;
    private final String inputObjectKey;
    private final Long projectId;
    private final Long branchId;
    private final Long snapshotId;
    private JobStatus status;
    private int progress;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant heartbeatAt;
    private String ownerInstance;
    private String errorCode;
    private String errorMessage;

    private BackgroundJob(JobSnapshot snapshot) {
        this.id = snapshot.id();
        this.type = snapshot.type();
        this.inputObjectKey = snapshot.inputObjectKey();
        this.projectId = snapshot.projectId();
        this.branchId = snapshot.branchId();
        this.snapshotId = snapshot.snapshotId();
        this.status = snapshot.status();
        this.progress = snapshot.progress();
        this.startedAt = snapshot.startedAt();
        this.finishedAt = snapshot.finishedAt();
        this.heartbeatAt = snapshot.heartbeatAt();
        this.ownerInstance = snapshot.ownerInstance();
        this.errorCode = snapshot.errorCode();
        this.errorMessage = snapshot.errorMessage();
    }

    /**
     * 创建执行前必须先持久化的 PENDING 任务。
     *
     * @param id 新任务 ID
     * @param type 已注册任务类型
     * @param inputObjectKey 可选输入对象键
     * @param createdAt 创建时刻，用于校验调用方已提供时间；创建审计由仓储保存
     * @return 待执行任务
     */
    public static BackgroundJob pending(Long id, String type, String inputObjectKey, Instant createdAt) {
        return pending(id, type, inputObjectKey, null, null, null, createdAt);
    }

    /**
     * 创建带项目、分支和快照范围的 PENDING 任务；范围会随状态快照持久化并传给处理器。
     *
     * @param id 新任务 ID
     * @param type 已注册任务类型
     * @param inputObjectKey 可选输入对象键
     * @param projectId 可选项目 Long
     * @param branchId 可选分支 Long
     * @param snapshotId 可选代码快照 Long
     * @param createdAt 创建 UTC 时刻
     * @return 待执行任务
     */
    public static BackgroundJob pending(
            Long id,
            String type,
            String inputObjectKey,
            Long projectId,
            Long branchId,
            Long snapshotId,
            Instant createdAt
    ) {
        Objects.requireNonNull(createdAt, "任务创建时间不能为空");
        if (type == null || type.isBlank() || type.length() > 64) {
            throw invalid("任务类型长度必须在 1 到 64 之间");
        }
        return new BackgroundJob(new JobSnapshot(
                Objects.requireNonNull(id, "任务 ID 不能为空"), type, JobStatus.PENDING, 0,
                inputObjectKey, projectId, branchId, snapshotId,
                null, null, null, null, null, null
        ));
    }

    /**
     * 从持久化快照恢复领域对象。
     *
     * @param snapshot 持久状态
     * @return 可继续应用状态规则的任务
     */
    public static BackgroundJob restore(JobSnapshot snapshot) {
        return new BackgroundJob(Objects.requireNonNull(snapshot, "任务快照不能为空"));
    }

    /**
     * 由执行实例认领待处理任务。
     *
     * @param now 开始 UTC 时刻
     * @param instanceId 执行实例标识
     */
    public void start(Instant now, String instanceId) {
        requireStatus(JobStatus.PENDING);
        this.status = JobStatus.RUNNING;
        this.startedAt = Objects.requireNonNull(now, "开始时间不能为空");
        this.heartbeatAt = now;
        this.ownerInstance = Objects.requireNonNull(instanceId, "执行实例不能为空");
    }

    /**
     * 单调更新运行进度；100 只由成功终结写入。
     *
     * @param newProgress 0 到 99 的新进度
     * @param now 心跳 UTC 时刻
     */
    public void updateProgress(int newProgress, Instant now) {
        requireStatus(JobStatus.RUNNING);
        if (newProgress < progress || newProgress > 99) {
            throw invalid("运行进度必须单调且位于 0 到 99");
        }
        this.progress = newProgress;
        this.heartbeatAt = Objects.requireNonNull(now, "进度时间不能为空");
    }

    /**
     * 刷新运行任务心跳。
     *
     * @param now 当前 UTC 时刻
     */
    public void heartbeat(Instant now) {
        requireStatus(JobStatus.RUNNING);
        this.heartbeatAt = Objects.requireNonNull(now, "心跳时间不能为空");
    }

    /**
     * 把运行任务终结为成功。
     *
     * @param now 完成 UTC 时刻
     */
    public void succeed(Instant now) {
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.SUCCEEDED;
        this.progress = 100;
        this.finishedAt = Objects.requireNonNull(now, "完成时间不能为空");
        this.heartbeatAt = now;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * 把运行任务终结为失败并保存已脱敏摘要。
     *
     * @param now 完成 UTC 时刻
     * @param code 稳定失败码
     * @param message 脱敏错误摘要
     */
    public void fail(Instant now, String code, String message) {
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.FAILED;
        this.finishedAt = Objects.requireNonNull(now, "完成时间不能为空");
        this.heartbeatAt = now;
        this.errorCode = Objects.requireNonNull(code, "错误码不能为空");
        this.errorMessage = Objects.requireNonNull(message, "错误摘要不能为空");
    }

    /**
     * 把运行任务终结为取消。
     *
     * @param now 完成 UTC 时刻
     */
    public void cancel(Instant now) {
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.CANCELLED;
        this.finishedAt = Objects.requireNonNull(now, "完成时间不能为空");
        this.heartbeatAt = now;
    }

    /**
     * @return 当前不可变任务快照
     */
    public JobSnapshot snapshot() {
        return new JobSnapshot(
                id, type, status, progress, inputObjectKey, projectId, branchId, snapshotId,
                startedAt, finishedAt,
                heartbeatAt, ownerInstance, errorCode, errorMessage
        );
    }

    private void requireStatus(JobStatus expected) {
        if (status != expected) {
            throw invalid("任务状态 " + status + " 不能执行要求 " + expected + " 的转换");
        }
    }

    private static InvalidJobTransitionException invalid(String message) {
        return new InvalidJobTransitionException(message);
    }
}
