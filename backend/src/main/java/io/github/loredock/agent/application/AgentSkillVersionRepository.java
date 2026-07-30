package io.github.loredock.agent.application;

import java.util.Optional;

/** Skill 版本元数据的持久化端口；发布状态以数据库为事实来源。 */
public interface AgentSkillVersionRepository {

    /** @return 指定名称和语义版本的元数据 */
    Optional<AgentSkillVersionMetadata> findByNameAndVersion(String name, String version);

    /** @return 指定名称唯一启用的元数据 */
    Optional<AgentSkillVersionMetadata> findEnabled(String name);

    /**
     * 原子发布新版本并将旧启用版本退役；同版本不同内容由唯一约束和上层校验拒绝。
     */
    void publish(AgentSkillVersionMetadata metadata);
}
