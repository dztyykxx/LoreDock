package io.github.loredock.agent.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEvidence;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.AnswerBasis;
import io.github.loredock.agent.domain.EvidenceSourceType;
import io.github.loredock.agent.domain.ProjectQaModelResult;
import io.github.loredock.agent.domain.ProjectQaResultValidator;
import io.github.loredock.agent.domain.TrustedProjectQaResult;
import io.github.loredock.platform.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * 业务目的：模型不可用、模型超时和越权工具必须保留各自稳定错误与实际用量，不能统一吞成无效响应。
     */
    @Test
    void executionFailureMatrixPreservesStableTerminalFacts() {
        for (AgentErrorCode code : List.of(
                AgentErrorCode.AGENT_MODEL_UNAVAILABLE,
                AgentErrorCode.AGENT_RUN_TIMEOUT,
                AgentErrorCode.AGENT_TOOL_NOT_ALLOWED)) {
            AgentExecutionPort execution = mock(AgentExecutionPort.class);
            AgentRunRepository runs = mock(AgentRunRepository.class);
            AgentEventRepository events = mock(AgentEventRepository.class);
            TimeProvider time = mock(TimeProvider.class);
            Instant now = Instant.parse("2026-07-30T04:00:00Z");
            AgentExecutionUsage usage = new AgentExecutionUsage(2, 1, 0, 0, null, null, 80);
            when(time.now()).thenReturn(now);
            when(runs.markRunning(any(), any())).thenReturn(true);
            when(runs.finishWithError(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                    .thenReturn(true);
            RuntimeException failure = code == AgentErrorCode.AGENT_TOOL_NOT_ALLOWED
                    ? new AgentToolException(code) : new AgentExecutionException(code, usage);
            when(execution.execute(any(), any())).thenThrow(failure);
            ProjectQaRunTaskExecutor executor = new ProjectQaRunTaskExecutor(
                    Optional.of(execution), runs, events, mock(ProjectQaResultValidator.class), time);
            AgentExecutionRequest request = request(now.plusSeconds(30));
            boolean terminated = code == AgentErrorCode.AGENT_RUN_TIMEOUT;
            AgentExecutionUsage expectedUsage = code == AgentErrorCode.AGENT_TOOL_NOT_ALLOWED
                    ? AgentExecutionUsage.none() : usage;

            executor.execute(request);

            verify(runs).finishWithError(request.runId(), code, terminated, expectedUsage, now);
            verify(events).append(eq(request.runId()),
                    eq(terminated ? AgentEventType.RUN_TERMINATED : AgentEventType.RUN_FAILED), eq(code.name()), eq(now));
        }
        System.out.printf("测试证据：场景=执行失败矩阵，错误=%s，超时终态=TERMINATED，其余=FAILED%n",
                List.of(AgentErrorCode.AGENT_MODEL_UNAVAILABLE, AgentErrorCode.AGENT_RUN_TIMEOUT,
                        AgentErrorCode.AGENT_TOOL_NOT_ALLOWED));
    }

    /**
     * 业务目的：关键执行日志必须包含可核验计数和 traceId/runId，同时不得泄漏问题、回答、证据正文或敏感连接信息。
     */
    @Test
    void executionLogsExposeFactsWithoutSensitiveContent() {
        String sensitive = "SENSITIVE_QUESTION_ANSWER_EVIDENCE_KEY_ENDPOINT_PATH";
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentEventRepository events = mock(AgentEventRepository.class);
        ProjectQaResultValidator validator = mock(ProjectQaResultValidator.class);
        TimeProvider time = mock(TimeProvider.class);
        Instant now = Instant.parse("2026-07-30T04:00:00Z");
        AgentExecutionRequest request = request(now.plusSeconds(30));
        AgentEvidence item = new AgentEvidence(UUID.randomUUID(), request.runId(), EvidenceSourceType.KNOWLEDGE,
                true, 0.9, UUID.randomUUID(), null, "atlas", "main", null, null, sensitive, now);
        AgentExecutionUsage usage = new AgentExecutionUsage(3, 2, 1, 12, 30L, 10L, 90);
        AgentExecutionPort execution = (ignored, observer) -> new AgentExecutionResult(
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        sensitive, null, List.of(item.id())), List.of(item), usage);
        when(time.now()).thenReturn(now);
        when(runs.markRunning(any(), any())).thenReturn(true);
        when(runs.complete(any(), any(), any(), any())).thenReturn(true);
        when(validator.validate(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(new TrustedProjectQaResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        sensitive, null, List.of(item.id())));
        Logger logger = (Logger) LoggerFactory.getLogger(ProjectQaRunTaskExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ProjectQaRunTaskExecutor(Optional.of(execution), runs, events, validator, time).execute(request);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs).contains("traceId=" + request.runId(), "runId=" + request.runId(),
                "evidenceCount=1", "citationCount=1", "stepCount=3", "modelCallCount=2",
                "tokenUsageKnown=true", "elapsedMs=90");
        assertThat(logs).doesNotContain(sensitive, "sk-", "https://", "/Users/", "objectKey");
        System.out.printf("测试证据：场景=执行日志脱敏，runId=%s，计数可核验=true，敏感正文出现=false%n",
                request.runId());
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
