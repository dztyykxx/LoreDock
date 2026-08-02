package io.github.loredock.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.service.ProjectQaToolService;
import io.github.loredock.agent.service.impl.ProjectQaAgentExecutor;
import java.io.IOException;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Agent 纯领域服务的 Spring 组装边界。 */
@Configuration(proxyBeanMethods = false)
public class AgentApplicationConfiguration {

    /**
     * @param dataSource 项目统一数据源
     * @return 只读接入 Flyway 已创建 Graph 表的框架 Checkpoint Saver
     * @throws SQLException 数据源不可用或协议表不兼容
     */
    @Bean
    public PostgresSaver agentCheckpointSaver(DataSource dataSource) throws SQLException {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .createOption(CreateOption.CREATE_NONE)
                .build();
    }

    /** @return 无状态的 project_qa 可信结果校验器 */
    @Bean
    public ProjectQaResultConverter projectQaResultValidator() {
        return new ProjectQaResultConverter();
    }

    /** @return 直接扫描随应用发布 Skill 的框架 classpath Registry */
    @Bean
    public ClasspathSkillRegistry projectQaSkillRegistry() {
        return ClasspathSkillRegistry.builder().classpathPath("agent-skills").build();
    }

    /**
     * @return project-qa 输出 JSON Schema；结构化业务结果仍由结果转换器执行可信校验
     * @throws IOException 内置资源缺失时阻止半配置应用启动
     */
    @Bean("projectQaOutputSchema")
    public String projectQaOutputSchema(ResourceLoader resources) throws IOException {
        try (var input = resources.getResource("classpath:agent-skills/project-qa/output-schema.json")
                .getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * OpenAI 模型显式启用时创建运行时；使用配置条件可避免按 Bean 注册先后判断而漏装生产运行时。
     *
     * @return 使用 Spring AI 标准 ChatModel 的 Alibaba Agent Runtime
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
    public ProjectQaAgentExecutor projectQaAgentExecutor(
            ChatModel chatModel,
            ProjectQaToolService tools,
            ObjectMapper objectMapper,
            ClasspathSkillRegistry projectQaSkillRegistry
    ) {
        return new ProjectQaAgentExecutor(() -> chatModel, tools, objectMapper, projectQaSkillRegistry);
    }
}
