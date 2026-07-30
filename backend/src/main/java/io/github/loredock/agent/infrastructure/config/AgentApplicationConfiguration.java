package io.github.loredock.agent.infrastructure.config;

import io.github.loredock.agent.domain.ProjectQaResultValidator;
import io.github.loredock.agent.application.AgentExecutionPort;
import io.github.loredock.agent.application.ProjectQaToolRegistry;
import io.github.loredock.agent.infrastructure.model.DeepSeekChatModelFactory;
import io.github.loredock.agent.infrastructure.model.SpringAiAlibabaAgentExecutionAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Agent 纯领域服务的 Spring 组装边界。 */
@Configuration(proxyBeanMethods = false)
public class AgentApplicationConfiguration {

    /** @return 无状态的 project_qa 可信结果校验器 */
    @Bean
    public ProjectQaResultValidator projectQaResultValidator() {
        return new ProjectQaResultValidator();
    }

    /**
     * @return 延迟创建 DeepSeek 模型的执行端口；Agent 关闭或缺少密钥时不会建立外部连接
     */
    @Bean
    public AgentExecutionPort agentExecutionPort(
            AgentProperties properties,
            ProjectQaToolRegistry tools,
            ObjectMapper objectMapper
    ) {
        return new SpringAiAlibabaAgentExecutionAdapter(
                () -> DeepSeekChatModelFactory.create(properties.model()), tools, objectMapper);
    }
}
