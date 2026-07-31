package io.github.loredock.agent.skill;

/**
 * classpath 中的 Agent 定义，不包含数据库版本、对象键或启停状态。
 *
 * @param name 稳定任务名称
 * @param outputSchemaVersion 输出结构名称
 * @param maxSteps 定义允许的最大步骤数
 * @param instructions Agent 指令
 * @param outputSchema 结构化输出 JSON Schema
 */
public record AgentDefinition(
        String name,
        String outputSchemaVersion,
        int maxSteps,
        String instructions,
        String outputSchema
) {
}
