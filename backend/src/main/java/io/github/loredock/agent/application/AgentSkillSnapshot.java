package io.github.loredock.agent.application;

import java.util.UUID;

/** 由数据库元数据定位并校验过内容哈希的启用 Skill。 */
public record AgentSkillSnapshot(
        UUID id,
        String name,
        String version,
        String contentHash,
        String objectKey,
        String outputSchemaVersion,
        String markdown,
        String outputSchema
) {
}
