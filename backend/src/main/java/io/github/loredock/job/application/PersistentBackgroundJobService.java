package io.github.loredock.job.application;

import io.github.loredock.job.domain.BackgroundJob;
import io.github.loredock.job.domain.JobFailure;
import io.github.loredock.job.domain.JobSnapshot;
import io.github.loredock.job.domain.JobStatus;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.audit.ActorProvider;
import io.github.loredock.platform.audit.AuditMetadataFactory;
import io.github.loredock.platform.time.TimeProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL 持久记录配合有界线程池的单实例任务服务。每次状态变更通过仓储短事务完成，工作本身不持有数据库事务。
 */
@Service
public class PersistentBackgroundJobService implements BackgroundJobService, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistentBackgroundJobService.class);
    private static final String JOB_MDC_KEY = "jobId";

    private final JobRepository repository;
    private final Map<String, JobHandler> handlers;
    private final ThreadPoolExecutor executor;
    private final Duration shutdownAwait;
    private final TimeProvider timeProvider;
    private final ActorProvider actorProvider;
    private final AuditMetadataFactory auditFactory;
    private final JobFailureClassifier failureClassifier;
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 创建具有明确并发和队列上限的任务服务。
     *
     * @param repository 任务仓储
     * @param handlers 已注册工作处理器
     * @param properties 线程、队列和停机上限
     * @param timeProvider UTC 时间端口
     * @param actorProvider 当前操作者端口
     * @param auditFactory 审计值工厂
     * @param failureClassifier 任务异常分类器
     */
    public PersistentBackgroundJobService(
            JobRepository repository,
            List<JobHandler> handlers,
            JobProperties properties,
            TimeProvider timeProvider,
            ActorProvider actorProvider,
            AuditMetadataFactory auditFactory,
            JobFailureClassifier failureClassifier
    ) {
        this.repository = repository;
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
    public UUID submit(JobRequest request) {
        if (request == null || request.type() == null || !handlers.containsKey(request.type())) {
            throw new ApplicationException(ErrorCode.UNSUPPORTED_JOB_TYPE, "任务类型未注册");
        }
        UUID jobId = UUID.randomUUID();
        BackgroundJob job = BackgroundJob.pending(
                jobId, request.type(), request.inputObjectKey(), timeProvider.now());
        // 必须先提交数据库记录，执行器中的任何路径才能通过任务 ID 留下可追踪结果。
        repository.insertPending(job, auditFactory.created());
        try {
            executor.execute(() -> execute(jobId, handlers.get(request.type())));
        } catch (RejectedExecutionException exception) {
            failRejected(jobId);
        }
        return jobId;
    }

    @Override
    public Optional<JobSnapshot> find(UUID jobId) {
        return repository.find(jobId).map(BackgroundJob::snapshot);
    }

    @Override
    public void cancel(UUID jobId) {
        repository.find(jobId).ifPresent(job -> {
            if (job.snapshot().status() != JobStatus.RUNNING) {
                return;
            }
            job.cancel(timeProvider.now());
            repository.update(job, JobStatus.RUNNING, timeProvider.now(), actorProvider.currentActor());
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

    private void execute(UUID jobId, JobHandler handler) {
        MDC.put(JOB_MDC_KEY, jobId.toString());
        try {
            BackgroundJob job = repository.find(jobId).orElseThrow();
            job.start(timeProvider.now(), instanceId);
            if (!repository.update(job, JobStatus.PENDING, timeProvider.now(), actorProvider.currentActor())) {
                return;
            }
            handler.execute(new ExecutionContext(jobId, job.snapshot().inputObjectKey()));
            finishSucceeded(jobId);
        } catch (Exception exception) {
            finishFailed(jobId, exception);
        } finally {
            MDC.remove(JOB_MDC_KEY);
        }
    }

    private void finishSucceeded(UUID jobId) {
        repository.find(jobId).ifPresent(job -> {
            if (job.snapshot().status() != JobStatus.RUNNING) {
                return;
            }
            job.succeed(timeProvider.now());
            repository.update(job, JobStatus.RUNNING, timeProvider.now(), actorProvider.currentActor());
        });
    }

    private void finishFailed(UUID jobId, Exception exception) {
        repository.find(jobId).ifPresent(job -> {
            if (job.snapshot().status() != JobStatus.RUNNING) {
                return;
            }
            JobFailure failure = failureClassifier.classify(exception);
            job.fail(timeProvider.now(), failure.code(), failure.message());
            repository.update(job, JobStatus.RUNNING, timeProvider.now(), actorProvider.currentActor());
            LOGGER.error("background_job_failed jobId={} code={} diagnostic={}",
                    jobId, failure.code(), failure.message());
        });
    }

    private void failRejected(UUID jobId) {
        repository.find(jobId).ifPresent(job -> {
            job.start(timeProvider.now(), instanceId);
            if (!repository.update(job, JobStatus.PENDING, timeProvider.now(), actorProvider.currentActor())) {
                return;
            }
            job.fail(timeProvider.now(), "CAPACITY_EXCEEDED", "后台任务执行器已达到容量上限");
            repository.update(job, JobStatus.RUNNING, timeProvider.now(), actorProvider.currentActor());
        });
    }

    private Map<String, JobHandler> indexHandlers(List<JobHandler> handlerList) {
        Map<String, JobHandler> indexed = new HashMap<>();
        for (JobHandler handler : handlerList) {
            JobHandler previous = indexed.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("任务类型重复注册: " + handler.type());
            }
        }
        return Map.copyOf(indexed);
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

    private final class ExecutionContext implements JobExecutionContext {
        private final UUID jobId;
        private final String inputObjectKey;

        private ExecutionContext(UUID jobId, String inputObjectKey) {
            this.jobId = jobId;
            this.inputObjectKey = inputObjectKey;
        }

        @Override
        public UUID jobId() {
            return jobId;
        }

        @Override
        public String inputObjectKey() {
            return inputObjectKey;
        }

        @Override
        public void updateProgress(int progress) {
            BackgroundJob job = requireRunning();
            job.updateProgress(progress, timeProvider.now());
            if (!repository.update(job, JobStatus.RUNNING, timeProvider.now(), actorProvider.currentActor())) {
                throw new ApplicationException(ErrorCode.INVALID_JOB_TRANSITION, "任务进度更新与终态转换发生竞争");
            }
        }

        @Override
        public void heartbeat() {
            BackgroundJob job = requireRunning();
            job.heartbeat(timeProvider.now());
            if (!repository.update(job, JobStatus.RUNNING, timeProvider.now(), actorProvider.currentActor())) {
                throw new ApplicationException(ErrorCode.INVALID_JOB_TRANSITION, "任务心跳更新与终态转换发生竞争");
            }
        }

        private BackgroundJob requireRunning() {
            BackgroundJob job = repository.find(jobId).orElseThrow();
            if (job.snapshot().status() != JobStatus.RUNNING) {
                throw new ApplicationException(ErrorCode.INVALID_JOB_TRANSITION, "任务已不处于运行态");
            }
            return job;
        }
    }
}
