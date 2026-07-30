package io.github.loredock.agent.application;

import java.time.Duration;

/** 启动用例固定模型、策略和运行上限所需的应用配置端口。 */
public interface AgentRuntimeConfiguration {

    /** @return Agent 能力是否已显式开启 */
    boolean enabled();

    /** @return 生产模型端点和 secret 是否已配置 */
    boolean modelConfigured();

    /** @return 固定模型提供方描述 */
    String modelProvider();

    /** @return 固定模型名称 */
    String modelName();

    /** @return 输出 schema 版本 */
    String outputSchemaVersion();

    /** @return 只读工具策略版本 */
    String toolPolicyVersion();

    /** @return 资源限制策略版本 */
    String limitPolicyVersion();

    /** @return 单次运行固定硬上限 */
    AgentRuntimeLimits runtimeLimits();

    /** @return 单次运行总超时 */
    Duration totalTimeout();
}
