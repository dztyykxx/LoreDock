package io.github.loredock.job.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 后台任务资源上限配置，防止无界线程和队列耗尽服务资源。
 *
 * @param corePoolSize 核心线程数
 * @param maxPoolSize 最大线程数
 * @param queueCapacity 等待队列容量
 * @param shutdownAwait 优雅停机等待时间
 * @param staleHeartbeat 陈旧心跳阈值
 */
@Validated
@ConfigurationProperties("loredock.jobs")
public record JobProperties(
        @Min(1) @Max(16) int corePoolSize,
        @Min(1) @Max(32) int maxPoolSize,
        @Min(0) @Max(10_000) int queueCapacity,
        @NotNull Duration shutdownAwait,
        @NotNull Duration staleHeartbeat
) {
    public JobProperties {
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("最大线程数不能小于核心线程数");
        }
    }
}
