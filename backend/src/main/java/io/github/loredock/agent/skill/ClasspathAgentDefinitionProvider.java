package io.github.loredock.agent.skill;

import io.github.loredock.agent.service.AgentDefinitionProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** 从随应用发布的资源加载内置 Agent 定义，删除数据库发布、对象存储 bundle 和启用版本流程。 */
@Component
public class ClasspathAgentDefinitionProvider implements AgentDefinitionProvider {

    private static final String PROJECT_QA = "project_qa";
    private final Map<String, AgentDefinition> definitions;

    /**
     * @param resources Spring 资源读取器
     * @param validator project_qa 结构校验器
     * @throws IllegalStateException 内置资源缺失或结构无效，应用不得以半成品 Agent 启动
     */
    public ClasspathAgentDefinitionProvider(ResourceLoader resources, ProjectQaSkillValidator validator) {
        String markdown = read(resources.getResource("classpath:agent-skills/project_qa/SKILL.md"));
        String schema = read(resources.getResource("classpath:agent-skills/project_qa/output-schema.json"));
        ProjectQaSkillDefinition definition = validator.validate(markdown, schema);
        this.definitions = Map.of(PROJECT_QA, new AgentDefinition(
                definition.name(), definition.outputSchemaVersion(), definition.maxSteps(),
                definition.markdown(), definition.outputSchema()));
    }

    @Override
    public Optional<AgentDefinition> find(String taskType) {
        return Optional.ofNullable(definitions.get(taskType));
    }

    private String read(Resource resource) {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent 定义资源不可读取", exception);
        }
    }
}
