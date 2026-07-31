package io.github.loredock.job.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.job.api.JobService;
import io.github.loredock.job.config.JobProperties;
import io.github.loredock.job.mapper.BackgroundJobMapper;
import io.github.loredock.job.model.BackgroundJob;
import io.github.loredock.job.model.entity.BackgroundJobEntity;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.job.model.result.JobFailure;
import io.github.loredock.job.model.snapshot.JobSnapshot;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL 持久记录配合有界线程池的单实例任务服务。每次状态变更通过仓储短事务完成，工作本身不持有数据库事务。
 */
@Service
public class PersistentBackgroundJobService implements JobService, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistentBackgroundJobService.class);
    private static final String JOB_MDC_KEY = "jobId";

    private final BackgroundJobMapper jobs;
    private final Map<String, JobService.Handler> handlers;
    private final ThreadPoolExecutor executor;
    private final Duration shutdownAwait;
    private final Clock timeProvider;
    private final Supplier<String> actorProvider;
    private final AuditMetadataFactory auditFactory;
    private final JobFailureClassifier failureClassifier;
    private final String instanceId = java.util.UUID.randomUUID().toString();
    private final Object singleFlightLock = new Object();

    /**
     * 创建具有明确并发和队列上限的任务服务。
     *
     * @param jobs 后台任务表 Mapper
     * @param handlers 已注册工作处理器
     * @param properties 线程、队列和停机上限
     * @param timeProvider UTC 时间端口
     * @param actorProvider 当前操作者端口
     * @param auditFactory 审计值工厂
     * @param failureClassifier 任务异常分类器
     */
    public PersistentBackgroundJobService(
            BackgroundJobMapper jobs,
            List<JobService.Handler> handlers,
            JobProperties properties,
            Clock timeProvider,
            @Qualifier("auditActorSupplier") Supplier<String> actorProvider,
            AuditMetadataFactory auditFactory,
            JobFailureClassifier failureClassifier
    ) {
        this.jobs = jobs;
        this.handlers = indexHandlers(handlers);
        this.shutdownAwait = properties.shutdownAwait();
        this.timeProvider = timeProvider;
        this.actorProvider = actorProvider;
        this.auditFactory = auditFactory;
        this.failureClassifier = failureClassifier;
        this.executor = new ThreadPoolExecutor(
                properties.corePoolSize(),
                properties.maxPoolSize(),
                30,
                TimeUnit.SECONDS,
                workQueue(properties.queueCapacity()),
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public Long submit(JobService.Request request) {
        validateRequest(request);
        return submitNew(request);
    }

    @Override
    public Long submitSingleFlight(JobService.Request request) {
        validateRequest(request);
        // MVP 明确为单实例部署：JVM 锁把“查活动任务—插入新任务”串行化，不宣称提供分布式互斥。
        synchronized (singleFlightLock) {
            return findActiveJob(request.type())
                    .map(job -> job.snapshot().id())
                    .orElseGet(() -> submitNew(request));
        }
    }

    @Override
    public Long submitExclusiveByBranch(JobService.Request request) {
        validateRequest(request);
        if (request.projectId() == null || request.branchId() == null || request.snapshotId() == null) {
            throw new IllegalArgumentException("分支排他任务必须包含项目、分支和快照范围");
        }
        return submitNew(request);
    }

    private Long submitNew(JobService.Request request) {
        // 必须先提交数据库记录，执行器中的任何路径才能通过任务 ID 留下可追踪结果。
        Long jobId;
        try {
            jobId = insertPending(request, auditFactory.created());
        } catch (DataIntegrityViolationException exception) {
            if (causedByNamedConstraint(exception, "uq_background_job_code_branch_active")) {
                throw new ApplicationException(
                        ErrorCode.CODE_SNAPSHOT_JOB_ACTIVE,
                        "同分支代码构建或重建任务已处于活动状态",
                        exception);
            }
            throw exception;
        }
        scheduleAfterCommit(jobId, handlers.get(request.type()));
        return jobId;
    }

    private void scheduleAfterCommit(Long jobId, JobService.Handler handler) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // 候选快照与任务可能由调用方同事务登记；只有提交成功后处理器才能安全读取它们。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(jobId, handler);
                }
            });
            return;
        }
        schedule(jobId, handler);
    }

    private void schedule(Long jobId, JobService.Handler handler) {
        try {
            executor.execute(() -> execute(jobId, handler));
        } catch (RejectedExecutionException exception) {
            failRejected(jobId);
        }
    }

    private void validateRequest(JobService.Request request) {
        if (request == null || request.type() == null || !handlers.containsKey(request.type())) {
            throw new ApplicationException(ErrorCode.UNSUPPORTED_JOB_TYPE, "任务类型未注册");
        }
    }

    @Override
    public Optional<JobService.Snapshot> find(Long jobId) {
        return findJob(jobId).map(BackgroundJob::snapshot).map(this::toApi);
    }

    @Override
    public Optional<JobService.Snapshot> findActiveByType(String type) {
        return findActiveJob(type).map(BackgroundJob::snapshot).map(this::toApi);
    }

    /**
     * 判断任务是否不存在或已经终结，供其他模块恢复自身派生状态时复用，避免跨模块读取任务表 Mapper。
     *
     * @param jobId 后台任务标识
     * @return 不存在或处于成功、失败、取消终态时为 {@code true}
     */
    @Override
    public boolean isMissingOrTerminal(Long jobId) {
        return find(jobId).map(snapshot -> switch (snapshot.status()) {
            case SUCCEEDED, FAILED, CANCELLED -> true;
            case PENDING, RUNNING -> false;
        }).orElse(true);
    }

    @Override
    public void cancel(Long jobId) {
        findJob(jobId).ifPresent(job -> {
            if (job.snapshot().status() != JobStatus.RUNNING) {
                return;
            }
            job.cancel(timeProvider.instant());
            updateJob(job, JobStatus.RUNNING, timeProvider.instant(), actorProvider.get());
        });
    }

    /**
     * 停止接收新任务，并在上限内等待运行中工作；超时后中断线程，持久 RUNNING 状态留待启动恢复处理。
     */
    @Override
    @PreDestroy
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownAwait.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void execute(Long jobId, JobService.Handler handler) {
        MDC.put(JOB_MDC_KEY, jobId.toString());
        try {
            BackgroundJob job = findJob(jobId).orElseThrow();
            job.start(timeProvider.instant(), instanceId);
            if (!updateJob(job, JobStatus.PENDING, timeProvider.instant(), actorProvider.get())) {
                return;
            }
            JobSnapshot snapshot = job.snapshot();
            handler.execute(new ExecutionContext(
                    jobId, snapshot.inputObjectKey(), snapshot.projectId(), snapshot.branchId(), snapshot.snapshotId()));
            finishSucceeded(jobId);
        } catch (Exception exception) {
            finishFailed(jobId, exception);
        } finally {
            MDC.remove(JOB_MDC_KEY);
        }
    }

    private void finishSucceeded(Long jobId) {
        findJob(jobId).ifPresent(job -> {
            if (job.snapshot().status() != JobStatus.RUNNING) {
                return;
            }
            job.succeed(timeProvider.instant());
            updateJob(job, JobStatus.RUNNING, timeProvider.instant(), actorProvider.get());
        });
    }

    private void finishFailed(Long jobId, Exception exception) {
        findJob(jobId).ifPresent(job -> {
            if (job.snapshot().status() != JobStatus.RUNNING) {
                return;
            }
            JobFailure failure = failureClassifier.classify(exception);
            job.fail(timeProvider.instant(), failure.code(), failure.message());
            updateJob(job, JobStatus.RUNNING, timeProvider.instant(), actorProvider.get());
            LOGGER.error("background_job_failed jobId={} code={} diagnostic={}",
                    jobId, failure.code(), failure.message());
        });
    }

    private void failRejected(Long jobId) {
        findJob(jobId).ifPresent(job -> {
            job.start(timeProvider.instant(), instanceId);
            if (!updateJob(job, JobStatus.PENDING, timeProvider.instant(), actorProvider.get())) {
                return;
            }
            job.fail(timeProvider.instant(), "CAPACITY_EXCEEDED", "后台任务执行器已达到容量上限");
            updateJob(job, JobStatus.RUNNING, timeProvider.instant(), actorProvider.get());
        });
    }

    private Long insertPending(JobService.Request request, AuditMetadata audit) {
        BackgroundJobEntity entity = BackgroundJobEntity.builder()
                .jobType(request.type()).status(JobStatus.PENDING.name()).progress(0)
                .inputObjectKey(request.inputObjectKey()).projectId(request.projectId())
                .branchId(request.branchId()).snapshotId(request.snapshotId())
                .createdAt(audit.createdAt()).updatedAt(audit.updatedAt())
                .createdBy(audit.createdBy()).updatedBy(audit.updatedBy()).build();
        jobs.insert(entity);
        return Objects.requireNonNull(entity.getId(), "后台任务写入后数据库未回填主键");
    }

    private Optional<BackgroundJob> findJob(Long jobId) {
        return Optional.ofNullable(jobs.selectById(jobId)).map(this::toDomain);
    }

    private Optional<BackgroundJob> findActiveJob(String type) {
        return Optional.ofNullable(jobs.selectOne(Wrappers.<BackgroundJobEntity>lambdaQuery()
                        .eq(BackgroundJobEntity::getJobType, type)
                        .in(BackgroundJobEntity::getStatus, JobStatus.PENDING.name(), JobStatus.RUNNING.name())
                        .orderByAsc(BackgroundJobEntity::getCreatedAt)
                        .orderByAsc(BackgroundJobEntity::getId).last("limit 1")))
                .map(this::toDomain);
    }

    private boolean updateJob(BackgroundJob job, JobStatus expectedStatus, java.time.Instant updatedAt, String updatedBy) {
        JobSnapshot snapshot = job.snapshot();
        return jobs.update(Wrappers.<BackgroundJobEntity>lambdaUpdate()
                .eq(BackgroundJobEntity::getId, snapshot.id())
                .eq(BackgroundJobEntity::getStatus, expectedStatus.name())
                .set(BackgroundJobEntity::getStatus, snapshot.status().name())
                .set(BackgroundJobEntity::getProgress, snapshot.progress())
                .set(BackgroundJobEntity::getStartedAt, snapshot.startedAt())
                .set(BackgroundJobEntity::getFinishedAt, snapshot.finishedAt())
                .set(BackgroundJobEntity::getHeartbeatAt, snapshot.heartbeatAt())
                .set(BackgroundJobEntity::getOwnerInstance, snapshot.ownerInstance())
                .set(BackgroundJobEntity::getErrorCode, snapshot.errorCode())
                .set(BackgroundJobEntity::getErrorMessage, snapshot.errorMessage())
                .set(BackgroundJobEntity::getUpdatedAt, updatedAt)
                .set(BackgroundJobEntity::getUpdatedBy, updatedBy)) == 1;
    }

    private BackgroundJob toDomain(BackgroundJobEntity entity) {
        return BackgroundJob.restore(new JobSnapshot(
                entity.getId(), entity.getJobType(), JobStatus.valueOf(entity.getStatus()), entity.getProgress(),
                entity.getInputObjectKey(), entity.getProjectId(), entity.getBranchId(), entity.getSnapshotId(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getHeartbeatAt(), entity.getOwnerInstance(),
                entity.getErrorCode(), entity.getErrorMessage()));
    }

    private JobService.Snapshot toApi(JobSnapshot snapshot) {
        return new JobService.Snapshot(
                snapshot.id(), snapshot.type(), JobService.Status.valueOf(snapshot.status().name()),
                snapshot.progress(), snapshot.projectId(), snapshot.branchId(), snapshot.snapshotId(),
                snapshot.startedAt(), snapshot.finishedAt(), snapshot.heartbeatAt(),
                snapshot.errorCode(), snapshot.errorMessage());
    }

    private Map<String, JobService.Handler> indexHandlers(List<JobService.Handler> handlerList) {
        Map<String, JobService.Handler> indexed = new HashMap<>();
        for (JobService.Handler handler : handlerList) {
            JobService.Handler previous = indexed.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("任务类型重复注册: " + handler.type());
            }
        }
        return Map.copyOf(indexed);
    }

    private boolean causedByNamedConstraint(Throwable failure, String constraintName) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private BlockingQueue<Runnable> workQueue(int capacity) {
        return capacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(capacity);
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "loredock-job-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private final class ExecutionContext implements JobService.ExecutionContext {
        private final Long jobId;
        private final String inputObjectKey;
        private final Long projectId;
        private final Long branchId;
        private final Long snapshotId;

        private ExecutionContext(
                Long jobId, String inputObjectKey, Long projectId, Long branchId, Long snapshotId
        ) {
            this.jobId = jobId;
            this.inputObjectKey = inputObjectKey;
            this.projectId = projectId;
            this.branchId = branchId;
            this.snapshotId = snapshotId;
        }

        @Override
        public Long jobId() {
            return jobId;
        }

        @Override
        public String inputObjectKey() {
            return inputObjectKey;
        }

        @Override
        public Long projectId() {
            return projectId;
        }

        @Override
        public Long branchId() {
            return branchId;
        }

        @Override
        public Long snapshotId() {
            return snapshotId;
        }

        @Override
        public void updateProgress(int progress) {
            BackgroundJob job = requireRunning();
            job.updateProgress(progress, timeProvider.instant());
            if (!updateJob(job, JobStatus.RUNNING, timeProvider.instant(), actorProvider.get())) {
                throw new ApplicationException(ErrorCode.INVALID_JOB_TRANSITION, "任务进度更新与终态转换发生竞争");
            }
        }

        @Override
        public void heartbeat() {
            BackgroundJob job = requireRunning();
            job.heartbeat(timeProvider.instant());
            if (!updateJob(job, JobStatus.RUNNING, timeProvider.instant(), actorProvider.get())) {
                throw new ApplicationException(ErrorCode.INVALID_JOB_TRANSITION, "任务心跳更新与终态转换发生竞争");
            }
        }

        private BackgroundJob requireRunning() {
            BackgroundJob job = findJob(jobId).orElseThrow();
            if (job.snapshot().status() != JobStatus.RUNNING) {
                throw new ApplicationException(ErrorCode.INVALID_JOB_TRANSITION, "任务已不处于运行态");
            }
            return job;
        }
    }
}
