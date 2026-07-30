package io.github.loredock.agent.application;

import java.util.Optional;

/** 查询当前启用且内容可读、哈希匹配的内置 Skill。 */
public interface AgentSkillCatalog {

    /** @return 指定名称的唯一启用 Skill；不可用或校验失败时为空 */
    Optional<AgentSkillSnapshot> findEnabled(String name);
}
