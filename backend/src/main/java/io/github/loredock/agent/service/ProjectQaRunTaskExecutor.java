package io.github.loredock.agent.service;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.exception.AgentExecutionException;
import io.github.loredock.agent.exception.AgentToolException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import io.github.loredock.agent.service.impl.ProjectQaAgentExecutor;
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

    private final Optional<ProjectQaAgentExecutor> execution;
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
            Optional<ProjectQaAgentExecutor> execution,
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
            events.append(request.runId(), AgentEventType.MODEL_STARTED, AgentEvent.SubjectType.MODEL,
                    payload("GENERATING", "project_qa_model", null, "STARTED", null, null), startedAt);
            AgentExecutionResult result = execution.get().execute(request, delta -> events.append(
                    request.runId(), AgentEventType.ANSWER_DELTA, AgentEvent.SubjectType.MODEL,
                    new AgentEvent.Payload("ANSWERING", null, null, null, null, null, null, "UNVERIFIED",
                            java.util.List.of(), null, delta, null, null, true, false),
                    timeProvider.instant()));
            Instant finishedAt = timeProvider.instant();
            if (finishedAt.isAfter(request.deadline())) {
                fail(request, AgentErrorCode.AGENT_RUN_TIMEOUT, finishedAt, result.usage());
                return;
            }
            TrustedProjectQaResult trusted = validator.validate(
                    request.runId(), result.modelResult(), result.evidence());
            events.append(request.runId(), AgentEventType.CITATION_VALIDATION, AgentEvent.SubjectType.VALIDATOR,
                    payload("VALIDATING", "citation_validator", trusted.citations().size(),
                            validationStatus(result, trusted), null, null),
                    finishedAt);
            events.append(request.runId(), AgentEventType.PUBLIC_DECISION_SUMMARY, AgentEvent.SubjectType.MODEL,
                    new AgentEvent.Payload("REASONING", null, null, null, null, null, null, "COMPLETED",
                            java.util.List.of(), publicSummary(trusted), null, null, null, true, false), finishedAt);
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

    private AgentEvent.Payload payload(
            String phase, String name, Integer count, String status, String textDelta, String resultType
    ) {
        return new AgentEvent.Payload(phase, name, null, null, null, count, null, status,
                java.util.List.of(), null, textDelta,
                resultType == null ? null : io.github.loredock.agent.api.AgentRun.ResultType.valueOf(resultType),
                null, false, false);
    }

    private String publicSummary(TrustedProjectQaResult trusted) {
        return switch (trusted.resultType()) {
            case ANSWER -> trusted.basis() == null
                    ? "普通对话无需检索项目知识" : "已完成来源与引用校验";
            case REFUSAL -> "证据不足，已按规则拒答";
        };
    }

    private String validationStatus(AgentExecutionResult result, TrustedProjectQaResult trusted) {
        if (result.modelResult().resultType() == io.github.loredock.agent.model.enums.AgentResultType.ANSWER
                && trusted.resultType() == io.github.loredock.agent.model.enums.AgentResultType.REFUSAL) {
            return "FAILED";
        }
        return trusted.resultType() == io.github.loredock.agent.model.enums.AgentResultType.ANSWER
                && trusted.basis() == null ? "NOT_REQUIRED" : "PASSED";
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
