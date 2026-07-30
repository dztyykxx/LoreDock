package io.github.loredock.agent.domain;

import java.util.UUID;

/**
 * 运行开始后不可变化的 Skill、模型、输出和限制版本。
 *
 * @param skillVersionId Skill 元数据标识
 * @param skillName Skill 名称
 * @param skillVersion Skill 版本
 * @param skillContentHash Skill 内容哈希
 * @param modelProvider 模型提供方描述
 * @param modelName 模型名
 * @param outputSchemaVersion 输出结构版本
 * @param toolPolicyVersion 工具白名单版本
 * @param limitPolicyVersion 运行限制版本
 */
public record AgentVersionSnapshot(
        UUID skillVersionId,
        String skillName,
        String skillVersion,
        String skillContentHash,
        String modelProvider,
        String modelName,
        String outputSchemaVersion,
        String toolPolicyVersion,
        String limitPolicyVersion
) {
}
