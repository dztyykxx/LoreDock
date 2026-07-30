package io.github.loredock.agent.infrastructure.runtime;

import io.github.loredock.agent.application.AgentExecutionRequest;
import io.github.loredock.agent.application.AgentRunTaskExecutor;
import io.github.loredock.agent.application.AgentRuntimeLimits;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedAgentRunSchedulerTest {

    /**
     * 业务目的：Agent 必须运行在专用线程中，且执行容量用尽时明确拒绝，防止挤占 Web 请求线程或无界排队。
     */
    @Test
    void dedicatedSingleWorkerRejectsSecondRunWhenCapacityIsExhausted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        AgentRunTaskExecutor executor = request -> {
            workerName.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        BoundedAgentRunScheduler scheduler = new BoundedAgentRunScheduler(executor, properties());
        try {
            assertThat(scheduler.schedule(request())).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.schedule(request())).isFalse();
            assertThat(workerName.get()).startsWith("loredock-agent-");
            assertThat(workerName.get()).isNotEqualTo(Thread.currentThread().getName());
            System.out.printf("测试证据：场景=专用有界调度，执行线程=%s，第二个运行=拒绝%n", workerName.get());
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    private AgentProperties properties() {
        return new AgentProperties(true,
                new AgentProperties.Model("openai-compatible", "deepseek-v4-flash", "https://api.deepseek.com",
                        "process-only", Duration.ofSeconds(2), Duration.ofSeconds(10), 0),
                new AgentProperties.Policy("project-qa-v1", "project-qa-readonly-v1", "project-qa-policy-v1"),
                new AgentProperties.Limits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200, 0.1),
                new AgentProperties.Executor(1, 1, 0, Duration.ofSeconds(2)));
    }

    private AgentExecutionRequest request() {
        UUID runId = UUID.randomUUID();
        return new AgentExecutionRequest(runId, "question", "skill", "schema",
                new AgentScopeSnapshot(UUID.randomUUID(), "atlas", UUID.randomUUID(), "main",
                        UUID.randomUUID(), "abcdef1", null, List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "a".repeat(64),
                        "openai-compatible", "deepseek-v4-flash", "project-qa-v1",
                        "project-qa-readonly-v1", "project-qa-policy-v1"),
                new AgentRuntimeLimits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200),
                Instant.now().plusSeconds(30));
    }
}
