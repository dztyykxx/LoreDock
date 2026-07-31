package io.github.loredock.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.service.AgentRuntime;
import io.github.loredock.agent.service.ProjectQaToolService;
import io.github.loredock.agent.service.impl.SpringAiAlibabaAgentRuntime;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    /**
     * @return 使用 Spring AI 标准 ChatModel 的 Alibaba Agent Runtime
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    public AgentRuntime agentRuntime(
            ChatModel chatModel,
            ProjectQaToolService tools,
            ObjectMapper objectMapper
    ) {
        return new SpringAiAlibabaAgentRuntime(() -> chatModel, tools, objectMapper);
    }
}
