package io.github.loredock.job.infrastructure;

import io.github.loredock.job.application.JobProperties;
import io.github.loredock.job.application.JobRepository;
import io.github.loredock.platform.audit.ActorProvider;
import io.github.loredock.platform.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 在应用就绪时终结上次进程遗留的陈旧运行任务。
 *
 * <p>恢复只修改持久状态，不向执行器重新提交任务；具体任务尚未声明幂等规则时，自动重放可能
 * 重复产生导入或索引副作用。</p>
 */
@Component
public class JobRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobRecovery.class);

    private final JobRepository repository;
    private final JobProperties properties;
    private final TimeProvider timeProvider;
    private final ActorProvider actorProvider;

    /**
     * @param repository 任务持久化端口
     * @param properties 心跳失效阈值配置
     * @param timeProvider 可替换 UTC 时间端口
     * @param actorProvider 当前审计操作者
     */
    public JobRecovery(
            JobRepository repository,
            JobProperties properties,
            TimeProvider timeProvider,
            ActorProvider actorProvider
    ) {
        this.repository = repository;
        this.properties = properties;
        this.timeProvider = timeProvider;
        this.actorProvider = actorProvider;
    }

    /**
     * 将心跳早于阈值的 RUNNING 任务标为进程中断失败。
     *
     * @return 本次恢复的任务数量
     */
    @Transactional
    public int recoverStale() {
        Instant recoveredAt = timeProvider.now();
        Instant heartbeatBefore = recoveredAt.minus(properties.staleHeartbeat());
        int recovered = repository.failStaleRunning(
                heartbeatBefore,
                recoveredAt,
                actorProvider.currentActor()
        );
        LOGGER.info("background_job_recovery completed recoveredCount={}", recovered);
        return recovered;
    }

    /**
     * Flyway 和应用上下文完全启动后执行恢复，避免在迁移完成前访问任务表。
     */
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        recoverStale();
    }
}
