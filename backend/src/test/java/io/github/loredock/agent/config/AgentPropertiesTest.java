package io.github.loredock.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AgentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "loredock.agent.enabled=false",
                    "loredock.agent.model.provider=openai-compatible",
                    "loredock.agent.model.name=deepseek-v4-flash",
                    "loredock.agent.model.base-url=",
                    "loredock.agent.model.api-key=",
                    "loredock.agent.model.connect-timeout=5s",
                    "loredock.agent.model.read-timeout=60s",
                    "loredock.agent.model.max-retries=1",
                    "loredock.agent.policy.output-schema-version=project-qa-v1",
                    "loredock.agent.policy.tool-policy-version=project-qa-readonly-v1",
                    "loredock.agent.policy.limit-policy-version=project-qa-policy-v1",
                    "loredock.agent.limits.max-steps=8",
                    "loredock.agent.limits.max-model-calls=8",
                    "loredock.agent.limits.curation-max-tool-calls=48",
                    "loredock.agent.limits.curation-max-model-calls=24",
                    "loredock.agent.limits.total-timeout=90s",
                    "loredock.agent.limits.max-results-per-tool=10",
                    "loredock.agent.limits.max-snippet-characters=2000",
                    "loredock.agent.limits.max-context-characters=24000",
                    "loredock.agent.limits.max-answer-characters=8000",
                    "loredock.agent.limits.max-events=200",
                    "loredock.agent.limits.minimum-relevance=0.2",
                    "loredock.agent.executor.core-pool-size=2",
                    "loredock.agent.executor.max-pool-size=4",
                    "loredock.agent.executor.queue-capacity=16",
                    "loredock.agent.executor.shutdown-await=30s");

    /**
     * 业务目的：MVP 默认关闭 Agent 且没有模型密钥时，强类型配置仍应正常启动并明确模型不可用。
     */
    @Test
    void disabledAgentWithoutModelSecretBindsSuccessfully() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AgentProperties properties = context.getBean(AgentProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.model().configured()).isFalse();
            assertThat(properties.limits().runtimeLimits().maxSteps()).isEqualTo(8);
            assertThat(properties.limits().curationMaxToolCalls()).isEqualTo(48);
            assertThat(properties.limits().curationMaxModelCalls()).isEqualTo(24);
            System.out.println("测试证据：场景=Agent默认关闭且无secret，配置绑定=成功，"
                    + "短问答步骤上限=8，知识整理Tool上限=48，知识整理模型上限=24");
        });
    }

    /**
     * 业务目的：部署配置不得把步骤数、重试或执行器扩大到服务端硬上限之外。
     */
    @Test
    void configurationBeyondServerCapsFailsStartup() {
        contextRunner.withPropertyValues("loredock.agent.limits.max-steps=21")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("loredock.agent.limits.curation-max-tool-calls=65")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("loredock.agent.limits.max-events=99")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new AgentProperties.Model(
                "openai-compatible", "deepseek-v4-flash", "https://example.invalid", "secret",
                Duration.ofSeconds(5), Duration.ofSeconds(60), 3))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new AgentProperties.Executor(4, 2, 16, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最大线程数");
        assertThatThrownBy(() -> new AgentProperties.Policy("unknown-schema"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未经规格");
        System.out.println("测试证据：场景=Agent配置越界，超限步骤/重试/线程均被拒绝");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class PropertiesConfiguration {
    }
}
