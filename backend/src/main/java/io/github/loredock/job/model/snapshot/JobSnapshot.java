package io.github.loredock.job.model.snapshot;

import io.github.loredock.job.model.enums.JobStatus;
import java.time.Instant;

/**
 * 调用方可读取的持久化任务状态快照。
 *
 * @param id 任务 ID
 * @param type 任务类型
 * @param status 当前状态
 * @param progress 0 到 100 的单调进度
 * @param inputObjectKey 可选输入对象键
 * @param projectId 可选项目范围
 * @param branchId 可选分支范围
 * @param snapshotId 可选代码快照范围
 * @param startedAt 开始 UTC 时刻
 * @param finishedAt 完成 UTC 时刻
 * @param heartbeatAt 最近心跳 UTC 时刻
 * @param ownerInstance 执行实例标识
 * @param errorCode 稳定错误码
 * @param errorMessage 脱敏诊断摘要
 */
public record JobSnapshot(
        Long id,
        String type,
        JobStatus status,
        int progress,
        String inputObjectKey,
        Long projectId,
        Long branchId,
        Long snapshotId,
        Instant startedAt,
        Instant finishedAt,
        Instant heartbeatAt,
        String ownerInstance,
        String errorCode,
        String errorMessage
) {
    /** 保留 T1 无范围任务的恢复与测试构造方式。 */
    public JobSnapshot(
            Long id,
            String type,
            JobStatus status,
            int progress,
            String inputObjectKey,
            Instant startedAt,
            Instant finishedAt,
            Instant heartbeatAt,
            String ownerInstance,
            String errorCode,
            String errorMessage
    ) {
        this(id, type, status, progress, inputObjectKey, null, null, null,
                startedAt, finishedAt, heartbeatAt, ownerInstance, errorCode, errorMessage);
    }
}
