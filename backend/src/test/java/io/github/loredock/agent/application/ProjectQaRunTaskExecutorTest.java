package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.ProjectQaResultValidator;
import io.github.loredock.platform.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectQaRunTaskExecutorTest {

    /**
     * 业务目的：工具发现 generation/snapshot 已切换时，运行必须终结且不得被统一改写为模型响应错误。
     */
    @Test
    void evidenceVersionChangeTerminatesRunWithOriginalStableCode() {
        AgentExecutionPort execution = mock(AgentExecutionPort.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentEventRepository events = mock(AgentEventRepository.class);
        TimeProvider time = mock(TimeProvider.class);
        Instant now = Instant.parse("2026-07-30T04:00:00Z");
        when(time.now()).thenReturn(now);
        when(runs.markRunning(any(), any())).thenReturn(true);
        when(runs.finishWithError(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(true);
        when(execution.execute(any(), any())).thenThrow(
                new AgentToolException(AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED));
        ProjectQaRunTaskExecutor executor = new ProjectQaRunTaskExecutor(Optional.of(execution), runs, events,
                mock(ProjectQaResultValidator.class), time);
        AgentExecutionRequest request = request(now.plusSeconds(30));

        executor.execute(request);

        verify(runs).finishWithError(request.runId(), AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED, true,
                AgentExecutionUsage.none(), now);
        verify(events).append(request.runId(), AgentEventType.RUN_TERMINATED,
                AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED.name(), now);
        System.out.printf("测试证据：场景=执行中证据版本变化，runId=%s，终态=TERMINATED，错误=%s%n",
                request.runId(), AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED);
    }

    private AgentExecutionRequest request(Instant deadline) {
        return new AgentExecutionRequest(UUID.randomUUID(), "question", "skill", "schema",
                new AgentScopeSnapshot(UUID.randomUUID(), "atlas", UUID.randomUUID(), "main",
                        UUID.randomUUID(), "abcdef1", UUID.randomUUID(),
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "a".repeat(64),
                        "openai-compatible", "deepseek-v4-flash", "project-qa-v1",
                        "project-qa-readonly-v1", "project-qa-policy-v1"),
                new AgentRuntimeLimits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200), deadline);
    }
}
