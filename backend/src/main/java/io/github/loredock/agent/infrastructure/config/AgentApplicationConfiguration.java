package io.github.loredock.agent.infrastructure.config;

import io.github.loredock.agent.domain.ProjectQaResultValidator;
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
}
