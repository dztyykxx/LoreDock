package io.github.loredock.agent.infrastructure.skill;

/** 经过结构校验的内置 `project_qa` Skill 内容。 */
public record ProjectQaSkillDefinition(
        String name,
        String version,
        String outputSchemaVersion,
        int maxSteps,
        String markdown,
        String outputSchema
) {
}
