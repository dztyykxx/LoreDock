package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.exception.AgentExecutionException;
import io.github.loredock.agent.exception.AgentToolException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.model.result.ProjectQaModelResult;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ProjectQaRunTaskExecutorTest {

    /**
     * 业务目的：工具发现 generation/snapshot 已切换时，运行必须终结且不得被统一改写为模型响应错误。
     */
    @Test
    void evidenceVersionChangeTerminatesRunWithOriginalStableCode() {
        AgentRuntime execution = mock(AgentRuntime.class);
        AgentRunService runs = mock(AgentRunService.class);
        AgentEventService events = mock(AgentEventService.class);
        Clock time = mock(Clock.class);
        Instant now = Instant.parse("2026-07-30T04:00:00Z");
        when(time.instant()).thenReturn(now);
        when(runs.markRunning(any(), any())).thenReturn(true);
        when(runs.finishWithError(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(true);
        when(execution.execute(any())).thenThrow(
                new AgentToolException(AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED));
        ProjectQaRunTaskExecutor executor = new ProjectQaRunTaskExecutor(Optional.of(execution), runs, events,
                mock(ProjectQaResultConverter.class), time);
        AgentExecutionRequest request = request(now.plusSeconds(30));

        executor.execute(request);

        verify(runs).finishWithError(request.runId(), AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED, true,
                AgentExecutionUsage.none(), now);
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
            AgentRuntime execution = mock(AgentRuntime.class);
            AgentRunService runs = mock(AgentRunService.class);
            AgentEventService events = mock(AgentEventService.class);
            Clock time = mock(Clock.class);
            Instant now = Instant.parse("2026-07-30T04:00:00Z");
            AgentExecutionUsage usage = new AgentExecutionUsage(2, 1, 0, 0, null, null, 80);
            when(time.instant()).thenReturn(now);
            when(runs.markRunning(any(), any())).thenReturn(true);
            when(runs.finishWithError(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                    .thenReturn(true);
            RuntimeException failure = code == AgentErrorCode.AGENT_TOOL_NOT_ALLOWED
                    ? new AgentToolException(code) : new AgentExecutionException(code, usage);
            when(execution.execute(any())).thenThrow(failure);
            ProjectQaRunTaskExecutor executor = new ProjectQaRunTaskExecutor(
                    Optional.of(execution), runs, events, mock(ProjectQaResultConverter.class), time);
            AgentExecutionRequest request = request(now.plusSeconds(30));
            boolean terminated = code == AgentErrorCode.AGENT_RUN_TIMEOUT;
            AgentExecutionUsage expectedUsage = code == AgentErrorCode.AGENT_TOOL_NOT_ALLOWED
                    ? AgentExecutionUsage.none() : usage;

            executor.execute(request);

            verify(runs).finishWithError(request.runId(), code, terminated, expectedUsage, now);
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
        AgentRunService runs = mock(AgentRunService.class);
        AgentEventService events = mock(AgentEventService.class);
        ProjectQaResultConverter validator = mock(ProjectQaResultConverter.class);
        Clock time = mock(Clock.class);
        Instant now = Instant.parse("2026-07-30T04:00:00Z");
        AgentExecutionRequest request = request(now.plusSeconds(30));
        AgentEvidence item = new AgentEvidence(8000000000000000099L, request.runId(), EvidenceSourceType.KNOWLEDGE,
                true, 0.9, 8000000000000000100L, null, "atlas", "main", null, null, sensitive, now);
        AgentExecutionUsage usage = new AgentExecutionUsage(3, 2, 1, 12, 30L, 10L, 90);
        AgentRuntime execution = ignored -> new AgentExecutionResult(
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        sensitive, null, List.of(item.id())), List.of(item), usage);
        when(time.instant()).thenReturn(now);
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
        return new AgentExecutionRequest(8000000000000000101L, "question", "skill", "schema",
                new AgentScopeSnapshot(8000000000000000102L, "atlas", 8000000000000000103L, "main",
                        8000000000000000104L, "abcdef1", 8000000000000000105L,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "deepseek-v4-flash", "project-qa-v1"),
                new AgentRuntimeLimits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200), deadline);
    }
}
