package io.github.loredock.job.api;

import java.time.Instant;
import java.util.Optional;

/**
 * code 与 knowledge 使用的持久化后台任务契约；隐藏线程池、任务表和执行实例信息。
 */
public interface JobService {

    /** @param request 已注册类型的任务输入 @return 新任务标识 */
    Long submit(Request request);

    /** @param request 已注册类型的任务输入 @return 同类型活动任务或新任务标识 */
    Long submitSingleFlight(Request request);

    /** @param request 带项目、分支和快照范围的任务输入 @return 新任务标识 */
    Long submitExclusiveByBranch(Request request);

    /** @param jobId 任务标识 @return 可见任务快照；不存在时为空 */
    Optional<Snapshot> find(Long jobId);

    /** @param type 任务类型 @return 最早活动任务；不存在时为空 */
    Optional<Snapshot> findActiveByType(String type);

    /** @param jobId 任务标识 @return 不存在或已终结时为 true */
    boolean isMissingOrTerminal(Long jobId);

    /** @param jobId 任务标识；不存在或非运行态时幂等返回 */
    void cancel(Long jobId);

    /** 后台任务生命周期。 */
    enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * @param type 已注册任务类型
     * @param inputObjectKey 可选输入对象键
     * @param projectId 可选项目范围
     * @param branchId 可选分支范围
     * @param snapshotId 可选代码快照范围
     */
    record Request(
            String type,
            String inputObjectKey,
            Long projectId,
            Long branchId,
            Long snapshotId
    ) {
        /** @param type 已注册任务类型 @param inputObjectKey 可选输入对象键 */
        public Request(String type, String inputObjectKey) {
            this(type, inputObjectKey, null, null, null);
        }
    }

    /**
     * @param id 任务标识
     * @param type 任务类型
     * @param status 当前状态
     * @param progress 0 到 100 的进度
     * @param projectId 可选项目范围
     * @param branchId 可选分支范围
     * @param snapshotId 可选代码快照范围
     * @param startedAt 开始时间
     * @param finishedAt 终态时间
     * @param heartbeatAt 最近心跳时间
     * @param errorCode 稳定错误码
     * @param errorMessage 脱敏失败摘要
     */
    record Snapshot(
            Long id,
            String type,
            Status status,
            int progress,
            Long projectId,
            Long branchId,
            Long snapshotId,
            Instant startedAt,
            Instant finishedAt,
            Instant heartbeatAt,
            String errorCode,
            String errorMessage
    ) {
    }

    /** 由具体业务模块注册的受控任务工作单元。 */
    interface Handler {
        /** @return 唯一任务类型 */
        String type();

        /** @param context 当前任务的受控执行上下文 @throws Exception 工作失败 */
        void execute(ExecutionContext context) throws Exception;
    }

    /** 处理器可用的范围、进度与心跳能力。 */
    interface ExecutionContext {
        /** @return 当前任务标识 */
        Long jobId();

        /** @return 可选输入对象键 */
        String inputObjectKey();

        /** @return 可选项目范围 */
        default Long projectId() { return null; }

        /** @return 可选分支范围 */
        default Long branchId() { return null; }

        /** @return 可选代码快照范围 */
        default Long snapshotId() { return null; }

        /** @param progress 单调的 0 到 99 执行进度 */
        void updateProgress(int progress);

        /** 刷新当前任务心跳。 */
        void heartbeat();
    }
}
