package io.github.loredock.agent.infrastructure.skill;

import io.github.loredock.agent.application.AgentSkillCatalog;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSkillBootstrapTest {

    /**
     * 业务目的：Skill 对象存储或元数据引导失败时只关闭 Agent，不得阻断既有应用启动。
     */
    @Test
    void publicationFailureLeavesAgentUnavailableWithoutFailingApplicationRunner() {
        BuiltinProjectQaSkillPublisher publisher = mock(BuiltinProjectQaSkillPublisher.class);
        AgentSkillCatalog catalog = mock(AgentSkillCatalog.class);
        when(publisher.publishBuiltin()).thenThrow(new IllegalStateException("simulated storage failure"));
        AgentSkillBootstrap bootstrap = new AgentSkillBootstrap(enabledProperties(), publisher, catalog);

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
        assertThat(bootstrap.isAvailable()).isFalse();
        System.out.println("测试证据：场景=Skill引导失败，应用Runner=正常返回，Agent可用=false");
    }

    private AgentProperties enabledProperties() {
        return new AgentProperties(true,
                new AgentProperties.Model("openai-compatible", "deepseek-v4-flash", "", "",
                        Duration.ofSeconds(5), Duration.ofSeconds(60), 1),
                new AgentProperties.Policy("project-qa-v1", "project-qa-readonly-v1", "project-qa-policy-v1"),
                new AgentProperties.Limits(8, 8, Duration.ofSeconds(90), 10,
                        2000, 24000, 8000, 200, 0.2),
                new AgentProperties.Executor(2, 4, 16, Duration.ofSeconds(30)));
    }
}
