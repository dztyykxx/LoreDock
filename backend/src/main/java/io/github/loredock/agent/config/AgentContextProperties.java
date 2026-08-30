package io.github.loredock.agent.config;

import io.github.loredock.agent.model.context.ContextBudget;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 知识整理上下文预算配置（设计文档 §3 配置表）。
 *
 * <p>所有阈值必须满足 {@code compressionTarget < compressionTrigger < maxInput}、
 * {@code maxInput + outputReserve + safetyReserve <= maxWindow} 且各上限大于 0，
 * 启动时注册即校验，配置不合法直接抛错终止启动，不尝试猜测模型窗口。</p>
 *
 * @param maxWindowTokens 单次模型调用总窗口（默认 128000）
 * @param maxInputTokens 单次调用发送前输入硬上限（默认 96000）
 * @param outputReserveTokens 单次调用输出预留（默认 24000）
 * @param safetyReserveTokens 协议与估算误差预留（默认 8000）
 * @param compressionTriggerTokens 节点入口开始确定性压缩的阈值（默认 72000）
 * @param compressionTargetTokens 节点入口确定性压缩后的目标（默认 64000）
 * @param maxRunInputTokens 一个 run 内全部模型调用的累计输入上限（默认 512000）
 * @param maxLlmCompressionCalls 一个 run 内 LLM 压缩兜底调用上限（默认 1）
 * @param maxRollingSummaryGenerations 连续滚动摘要代数上限（默认 3），达到后从原始消息低频重建
 */
@Validated
@ConfigurationProperties("loredock.agent.context")
public record AgentContextProperties(
        @DefaultValue("128000") int maxWindowTokens,
        @DefaultValue("96000") int maxInputTokens,
        @DefaultValue("24000") int outputReserveTokens,
        @DefaultValue("8000") int safetyReserveTokens,
        @DefaultValue("72000") int compressionTriggerTokens,
        @DefaultValue("64000") int compressionTargetTokens,
        @DefaultValue("512000") long maxRunInputTokens,
        @DefaultValue("1") int maxLlmCompressionCalls,
        @DefaultValue("3") int maxRollingSummaryGenerations
) {

    public AgentContextProperties {
        new ContextBudget(maxWindowTokens, maxInputTokens, outputReserveTokens, safetyReserveTokens,
                compressionTriggerTokens, compressionTargetTokens, maxRunInputTokens,
                maxLlmCompressionCalls, maxRollingSummaryGenerations).validateInvariants();
    }

    /** @return 向组装/守卫服务暴露的不变预算契约 */
    public ContextBudget budget() {
        return new ContextBudget(maxWindowTokens, maxInputTokens, outputReserveTokens, safetyReserveTokens,
                compressionTriggerTokens, compressionTargetTokens, maxRunInputTokens,
                maxLlmCompressionCalls, maxRollingSummaryGenerations);
    }
}
