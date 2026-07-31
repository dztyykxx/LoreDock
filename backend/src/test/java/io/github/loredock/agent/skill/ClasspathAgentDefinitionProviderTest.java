package io.github.loredock.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.service.AgentDefinitionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ClasspathAgentDefinitionProviderTest {

    /**
     * 业务目的：内置项目问答定义必须直接从 classpath 可用，避免数据库版本发布流程阻塞 Agent 启动。
     */
    @Test
    void projectQaDefinitionLoadsFromClasspath() {
        AgentDefinitionProvider provider = new ClasspathAgentDefinitionProvider(
                new DefaultResourceLoader(), new ProjectQaSkillValidator(new ObjectMapper()));

        var definition = provider.find("project_qa").orElseThrow();

        assertThat(definition.name()).isEqualTo("project_qa");
        assertThat(definition.instructions()).contains("knowledge_search", "当前知识库没有足够依据");
        assertThat(definition.outputSchema()).contains("project-qa-v1");
        System.out.printf("测试证据：场景=classpath Agent定义，名称=%s，最大步骤=%d，结构=%s%n",
                definition.name(), definition.maxSteps(), definition.outputSchemaVersion());
    }

    /**
     * 业务目的：未知任务必须返回明确的不存在结果，防止误用 project_qa 定义执行其他 Agent。
     */
    @Test
    void unknownTaskTypeDoesNotFallbackToAnotherDefinition() {
        AgentDefinitionProvider provider = new ClasspathAgentDefinitionProvider(
                new DefaultResourceLoader(), new ProjectQaSkillValidator(new ObjectMapper()));

        assertThat(provider.find("knowledge_mining")).isEmpty();
    }

    /**
     * 业务目的：定义结构校验失败必须阻止半成品 Agent 被加载，防止运行时才产生不可解释失败。
     */
    @Test
    void invalidDefinitionIsRejectedDeterministically() {
        ProjectQaSkillValidator validator = new ProjectQaSkillValidator(new ObjectMapper());

        assertThatThrownBy(() -> validator.validate("---\nname: project_qa\n---", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output_schema_version");
    }
}
