package io.github.loredock.agent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * T6A Agent 的服务端受控配置；客户端和模型不能在运行时提高这些上限。
 *
 * @param enabled 是否允许启动 Agent 运行
 * @param recordRetrievals 是否持久化每次知识检索实际提供给模型的内容（评估与审计用；为空视为开启）
 * @param model 生产模型描述与 secret 引用
 * @param policy 固定的输出、工具白名单和限制策略版本
 * @param limits 单次运行硬上限
 * @param executor 专用有界执行器上限
 */
@Validated
@ConfigurationProperties("loredock.agent")
public record AgentProperties(
        boolean enabled,
        Boolean recordRetrievals,
        @Valid @NotNull Model model,
        @Valid @NotNull Policy policy,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Executor executor
) {
    /** @return 是否持久化检索内容；未显式配置时按开启处理，保证评估可立即取数 */
    public boolean retrievalRecordingEnabled() {
        return recordRetrievals == null || recordRetrievals;
    }

    public boolean modelConfigured() {
        return model.configured();
    }

    public String modelProvider() {
        return model.provider();
    }

    public String modelName() {
        return model.name();
    }

    public String outputSchemaVersion() {
        return policy.outputSchemaVersion();
    }

    public AgentRuntimeLimits runtimeLimits() {
        return limits.runtimeLimits();
    }

    public Duration totalTimeout() {
        return limits.totalTimeout();
    }

    public double minimumRelevance() {
        return limits.minimumRelevance();
    }

    /** 输出结构名称必须与 classpath Agent 定义一致。 */
    public record Policy(@NotBlank String outputSchemaVersion) {
        public Policy {
            if (!"project-qa-v1".equals(outputSchemaVersion)) {
                throw new IllegalArgumentException("Agent 输出结构未经规格允许");
            }
        }
    }

    /** 仅允许 OpenAI 兼容模型描述；API Key 可为空，此时 Agent 明确不可用。 */
    public record Model(
            @NotBlank String provider,
            @NotBlank String name,
            String baseUrl,
            String apiKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Min(0) @Max(2) int maxRetries
    ) {
        public Model {
            if (!"openai-compatible".equals(provider)) {
                throw new IllegalArgumentException("仅允许 openai-compatible 模型提供方");
            }
            if (maxRetries < 0 || maxRetries > 2) {
                throw new IllegalArgumentException("模型重试次数超出服务端允许范围");
            }
            requireDuration(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30), "连接超时");
            requireDuration(readTimeout, Duration.ofSeconds(1), Duration.ofMinutes(2), "读取超时");
        }

        /** @return 生产模型密钥和端点是否已由部署环境提供 */
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }

    /** 单次运行的服务端资源限制。 */
    public record Limits(
            @Min(1) @Max(20) int maxSteps,
            @Min(1) @Max(20) int maxModelCalls,
            @Min(24) @Max(64) int curationMaxToolCalls,
            @Min(12) @Max(32) int curationMaxModelCalls,
            @NotNull Duration totalTimeout,
            @Min(1) @Max(20) int maxResultsPerTool,
            @Min(100) @Max(4_000) int maxSnippetCharacters,
            @Min(1_000) @Max(50_000) int maxContextCharacters,
            @Min(100) @Max(12_000) int maxAnswerCharacters,
            // 最大 20 步、每工具三类事件和 12000 字回答的结构上界低于 100，禁止配置得更小而截断可信结果事件。
            @Min(100) @Max(500) int maxEvents,
            @DecimalMin("0.0") @DecimalMax("1.0") double minimumRelevance
    ) {
        public Limits {
            requireDuration(totalTimeout, Duration.ofSeconds(1), Duration.ofMinutes(15), "运行总超时");
        }

        /** @return 不可变的应用层运行上限 */
        public AgentRuntimeLimits runtimeLimits() {
            return new AgentRuntimeLimits(maxSteps, maxModelCalls, totalTimeout, maxResultsPerTool,
                    maxSnippetCharacters, maxContextCharacters, maxAnswerCharacters, maxEvents);
        }
    }

    /** Agent 执行与索引任务分离，且线程和队列都有硬上限。 */
    public record Executor(
            @Min(1) @Max(4) int corePoolSize,
            @Min(1) @Max(8) int maxPoolSize,
            @Min(0) @Max(100) int queueCapacity,
            @NotNull Duration shutdownAwait
    ) {
        public Executor {
            if (maxPoolSize < corePoolSize) {
                throw new IllegalArgumentException("Agent 最大线程数不能小于核心线程数");
            }
            requireDuration(shutdownAwait, Duration.ofSeconds(1), Duration.ofMinutes(1), "Agent 停机等待");
        }
    }

    private static void requireDuration(Duration value, Duration minimum, Duration maximum, String label) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + "超出服务端允许范围");
        }
    }
}
