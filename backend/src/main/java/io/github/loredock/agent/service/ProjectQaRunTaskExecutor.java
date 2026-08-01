package io.github.loredock.agent.service;

import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.exception.AgentExecutionException;
import io.github.loredock.agent.exception.AgentToolException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 单运行应用编排：每次状态、证据与事件写入均是独立短事务，模型和工具等待不持有事务。
 */
@Service
@Slf4j
public class ProjectQaRunTaskExecutor {

    private final Optional<AgentRuntime> execution;
    private final AgentRunService runs;
    private final AgentEventService events;
    private final ProjectQaResultConverter validator;
    private final Clock timeProvider;

    /**
     * @param execution 可选模型适配端口；未配置时运行稳定失败
     * @param runs 运行仓储
     * @param events 事件仓储
     * @param validator 不可信模型结果校验器
     * @param timeProvider UTC 时间源
     */
    public ProjectQaRunTaskExecutor(
            Optional<AgentRuntime> execution,
            AgentRunService runs,
            AgentEventService events,
            ProjectQaResultConverter validator,
            Clock timeProvider
    ) {
        this.execution = execution;
        this.runs = runs;
        this.events = events;
        this.validator = validator;
        this.timeProvider = timeProvider;
    }

    public void execute(AgentExecutionRequest request) {
        Instant startedAt = timeProvider.instant();
        if (!runs.markRunning(request.runId(), startedAt)) {
            return;
        }
        if (execution.isEmpty()) {
            fail(request, AgentErrorCode.AGENT_MODEL_UNAVAILABLE, startedAt, AgentExecutionUsage.none());
            return;
        }
        try {
            events.append(request.runId(), AgentEventType.MODEL_STARTED, request.versions().modelName(), startedAt);
            AgentExecutionResult result = execution.get().execute(request);
            Instant finishedAt = timeProvider.instant();
            if (finishedAt.isAfter(request.deadline())) {
                fail(request, AgentErrorCode.AGENT_RUN_TIMEOUT, finishedAt, result.usage());
                return;
            }
            TrustedProjectQaResult trusted = validator.validate(
                    request.runId(), result.modelResult(), result.evidence());
            if (!runs.complete(request.runId(), trusted, result.usage(), finishedAt)) {
                return;
            }
            log.info("agent_run execution completed traceId={} runId={} project={} branch={} resultType={} "
                            + "evidenceCount={} citationCount={} stepCount={} modelCallCount={} tokenUsageKnown={} elapsedMs={}",
                    traceId(request), request.runId(), request.scope().projectIdentifier(), request.scope().branch(), trusted.resultType(),
                    result.evidence().size(), trusted.citations().size(), result.usage().stepCount(),
                    result.usage().modelCallCount(), tokenUsageKnown(result.usage()), result.usage().elapsedMillis());
        } catch (AgentToolException exception) {
            fail(request, exception.code(), timeProvider.instant(), AgentExecutionUsage.none());
        } catch (AgentExecutionException exception) {
            fail(request, exception.code(), timeProvider.instant(), exception.usage());
        } catch (RuntimeException exception) {
            fail(request, AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID, timeProvider.instant(), AgentExecutionUsage.none());
        }
    }

    private void fail(
            AgentExecutionRequest request,
            AgentErrorCode code,
            Instant finishedAt,
            AgentExecutionUsage usage
    ) {
        boolean terminated = code == AgentErrorCode.AGENT_RUN_TIMEOUT
                || code == AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED
                || code == AgentErrorCode.AGENT_STEP_LIMIT_EXCEEDED
                || code == AgentErrorCode.AGENT_MODEL_CALL_LIMIT_EXCEEDED;
        runs.finishWithError(request.runId(), code, terminated, usage, finishedAt);
        log.warn("agent_run execution failed traceId={} runId={} project={} branch={} errorCode={} "
                        + "stepCount={} modelCallCount={} tokenUsageKnown={} elapsedMs={}",
                traceId(request), request.runId(), request.scope().projectIdentifier(), request.scope().branch(), code,
                usage.stepCount(), usage.modelCallCount(), tokenUsageKnown(usage), usage.elapsedMillis());
    }

    private String traceId(AgentExecutionRequest request) {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? request.runId().toString() : current;
    }

    private boolean tokenUsageKnown(AgentExecutionUsage usage) {
        return usage.inputTokens() != null && usage.outputTokens() != null;
    }

}
