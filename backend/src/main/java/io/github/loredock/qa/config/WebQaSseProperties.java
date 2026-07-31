package io.github.loredock.qa.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** SSE 心跳、连接时限、历史事件批量读取和专用有界执行器配置。 */
@Validated
@ConfigurationProperties("loredock.qa.sse")
public record WebQaSseProperties(
        @NotNull Duration heartbeatInterval,
        @NotNull Duration maxDuration,
        @Min(1) @Max(200) int batchSize,
        @Min(1) @Max(4) int corePoolSize,
        @Min(1) @Max(16) int maxPoolSize,
        @Min(0) @Max(200) int queueCapacity,
        @NotNull Duration shutdownAwait
) {
    public WebQaSseProperties {
        requireDuration(heartbeatInterval, Duration.ofSeconds(5), Duration.ofMinutes(1), "SSE心跳间隔");
        requireDuration(maxDuration, Duration.ofMinutes(1), Duration.ofMinutes(10), "SSE连接时限");
        requireDuration(shutdownAwait, Duration.ofSeconds(1), Duration.ofMinutes(1), "SSE停机等待");
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("SSE最大线程数不能小于核心线程数");
        }
    }

    private static void requireDuration(Duration value, Duration minimum, Duration maximum, String label) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + "超出服务端允许范围");
        }
    }
}
