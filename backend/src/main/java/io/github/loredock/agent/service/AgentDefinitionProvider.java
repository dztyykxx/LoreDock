package io.github.loredock.agent.service;

import io.github.loredock.agent.skill.AgentDefinition;
import java.util.Optional;

/** Agent 定义替换边界；当前由 classpath 资源实现，后续多 Agent 可增加定义而不改运行时。 */
public interface AgentDefinitionProvider {

    /** @param taskType 稳定任务类型 @return 已校验的定义；未知任务返回空 */
    Optional<AgentDefinition> find(String taskType);
}
