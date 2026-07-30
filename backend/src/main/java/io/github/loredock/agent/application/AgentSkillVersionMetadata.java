package io.github.loredock.agent.application;

import java.time.Instant;
import java.util.UUID;

/** Skill 版本的数据库发布元数据，不包含 Markdown 正文。 */
public record AgentSkillVersionMetadata(
        UUID id,
        String name,
        String version,
        String contentHash,
        String objectKey,
        String outputSchemaVersion,
        String status,
        Instant createdAt
) {
}
