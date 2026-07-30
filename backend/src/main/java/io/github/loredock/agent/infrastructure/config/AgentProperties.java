package io.github.loredock.agent.infrastructure.config;

import io.github.loredock.agent.application.AgentRuntimeLimits;
import io.github.loredock.agent.application.AgentRuntimeConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * T6A Agent 的服务端受控配置；客户端和模型不能在运行时提高这些上限。
 *
 * @param enabled 是否允许启动 Agent 运行
 * @param model 生产模型描述与 secret 引用
 * @param policy 固定的输出、工具白名单和限制策略版本
 * @param limits 单次运行硬上限
 * @param executor 专用有界执行器上限
 */
@Validated
@ConfigurationProperties("loredock.agent")
public record AgentProperties(
        boolean enabled,
        @Valid @NotNull Model model,
        @Valid @NotNull Policy policy,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Executor executor
) implements AgentRuntimeConfiguration {
    @Override
    public boolean modelConfigured() {
        return model.configured();
    }

    @Override
    public String modelProvider() {
        return model.provider();
    }

    @Override
    public String modelName() {
        return model.name();
    }

    @Override
    public String outputSchemaVersion() {
        return policy.outputSchemaVersion();
    }

    @Override
    public String toolPolicyVersion() {
        return policy.toolPolicyVersion();
    }

    @Override
    public String limitPolicyVersion() {
        return policy.limitPolicyVersion();
    }

    @Override
    public AgentRuntimeLimits runtimeLimits() {
        return limits.runtimeLimits();
    }

    @Override
    public Duration totalTimeout() {
        return limits.totalTimeout();
    }

    @Override
    public double minimumRelevance() {
        return limits.minimumRelevance();
    }

    /** T6A 不允许部署环境换用未经规格验收的策略版本。 */
    public record Policy(
            @NotBlank String outputSchemaVersion,
            @NotBlank String toolPolicyVersion,
            @NotBlank String limitPolicyVersion
    ) {
        public Policy {
            if (!"project-qa-v1".equals(outputSchemaVersion)
                    || !"project-qa-readonly-v1".equals(toolPolicyVersion)
                    || !"project-qa-policy-v1".equals(limitPolicyVersion)) {
                throw new IllegalArgumentException("Agent 策略版本未经 T6A 规格允许");
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

    /** 与 `project-qa-policy-v1` 一起固定到运行的资源限制。 */
    public record Limits(
            @Min(1) @Max(20) int maxSteps,
            @Min(1) @Max(20) int maxModelCalls,
            @NotNull Duration totalTimeout,
            @Min(1) @Max(20) int maxResultsPerTool,
            @Min(100) @Max(4_000) int maxSnippetCharacters,
            @Min(1_000) @Max(50_000) int maxContextCharacters,
            @Min(100) @Max(12_000) int maxAnswerCharacters,
            @Min(10) @Max(500) int maxEvents,
            @DecimalMin("0.0") @DecimalMax("1.0") double minimumRelevance
    ) {
        public Limits {
            requireDuration(totalTimeout, Duration.ofSeconds(1), Duration.ofMinutes(5), "运行总超时");
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
