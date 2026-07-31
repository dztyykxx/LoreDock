package io.github.loredock.agent.model.snapshot;

/**
 * 运行使用的最小 Agent/模型事实，不再复制 Skill 版本、内容哈希和策略版本。
 *
 * @param agentName classpath Agent 定义名称
 * @param modelName 模型名称
 * @param configSummary 必要配置摘要
 */
public record AgentVersionSnapshot(String agentName, String modelName, String configSummary) {
}
