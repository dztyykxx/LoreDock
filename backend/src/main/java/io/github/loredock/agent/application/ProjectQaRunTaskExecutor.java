package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.ProjectQaResultValidator;
import io.github.loredock.agent.domain.TrustedProjectQaResult;
import io.github.loredock.platform.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 单运行应用编排：每次状态、证据与事件写入均是独立短事务，模型和工具等待不持有事务。
 */
@Service
@Slf4j
public class ProjectQaRunTaskExecutor implements AgentRunTaskExecutor {

    private static final int ANSWER_EVENT_CHUNK = 500;
    private final Optional<AgentExecutionPort> execution;
    private final AgentRunRepository runs;
    private final AgentEventRepository events;
    private final ProjectQaResultValidator validator;
    private final TimeProvider timeProvider;

    /**
     * @param execution 可选模型适配端口；未配置时运行稳定失败
     * @param runs 运行仓储
     * @param events 事件仓储
     * @param validator 不可信模型结果校验器
     * @param timeProvider UTC 时间源
     */
    public ProjectQaRunTaskExecutor(
            Optional<AgentExecutionPort> execution,
            AgentRunRepository runs,
            AgentEventRepository events,
            ProjectQaResultValidator validator,
            TimeProvider timeProvider
    ) {
        this.execution = execution;
        this.runs = runs;
        this.events = events;
        this.validator = validator;
        this.timeProvider = timeProvider;
    }

    @Override
    public void execute(AgentExecutionRequest request) {
        Instant startedAt = timeProvider.now();
        if (!runs.markRunning(request.runId(), startedAt)) {
            return;
        }
        events.append(request.runId(), AgentEventType.RUN_STARTED, "running", startedAt);
        events.append(request.runId(), AgentEventType.SKILL_PINNED,
                request.versions().skillName() + ":" + request.versions().skillVersion(), startedAt);
        if (execution.isEmpty()) {
            fail(request, AgentErrorCode.AGENT_MODEL_UNAVAILABLE, startedAt, AgentExecutionUsage.none());
            return;
        }
        try {
            events.append(request.runId(), AgentEventType.MODEL_STARTED, request.versions().modelName(), startedAt);
            AgentExecutionResult result = execution.get().execute(request,
                    (type, payload) -> events.append(request.runId(), type, payload, timeProvider.now()));
            Instant finishedAt = timeProvider.now();
            if (finishedAt.isAfter(request.deadline())) {
                fail(request, AgentErrorCode.AGENT_RUN_TIMEOUT, finishedAt, result.usage());
                return;
            }
            TrustedProjectQaResult trusted = validator.validate(
                    request.runId(), request.scope().hasCodeSnapshot(), result.modelResult(), result.evidence());
            if (!runs.complete(request.runId(), trusted, result.usage(), finishedAt)) {
                return;
            }
            publishTrustedResult(request, trusted, finishedAt);
            log.info("agent_run execution completed runId={} project={} branch={} resultType={} evidenceCount={} "
                            + "citationCount={} stepCount={} modelCallCount={} elapsedMs={}",
                    request.runId(), request.scope().projectIdentifier(), request.scope().branch(), trusted.resultType(),
                    result.evidence().size(), trusted.citations().size(), result.usage().stepCount(),
                    result.usage().modelCallCount(), result.usage().elapsedMillis());
        } catch (AgentToolException exception) {
            fail(request, exception.code(), timeProvider.now(), AgentExecutionUsage.none());
        } catch (RuntimeException exception) {
            fail(request, AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID, timeProvider.now(), AgentExecutionUsage.none());
        }
    }

    private void publishTrustedResult(AgentExecutionRequest request, TrustedProjectQaResult result, Instant finishedAt) {
        if (result.resultType() == AgentResultType.REFUSAL) {
            events.append(request.runId(), AgentEventType.REFUSAL, result.text(), finishedAt);
        } else {
            for (String chunk : chunks(result.text(), ANSWER_EVENT_CHUNK)) {
                events.append(request.runId(), AgentEventType.ANSWER_DELTA, chunk, finishedAt);
            }
        }
        events.append(request.runId(), AgentEventType.RUN_COMPLETED, result.resultType().name(), finishedAt);
    }

    private void fail(
            AgentExecutionRequest request,
            AgentErrorCode code,
            Instant finishedAt,
            AgentExecutionUsage usage
    ) {
        boolean terminated = code == AgentErrorCode.AGENT_RUN_TIMEOUT
                || code == AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED;
        if (runs.finishWithError(request.runId(), code, terminated, usage, finishedAt)) {
            events.append(request.runId(), terminated ? AgentEventType.RUN_TERMINATED : AgentEventType.RUN_FAILED,
                    code.name(), finishedAt);
        }
        log.warn("agent_run execution failed runId={} project={} branch={} errorCode={}",
                request.runId(), request.scope().projectIdentifier(), request.scope().branch(), code);
    }

    private java.util.List<String> chunks(String value, int maximumCodePoints) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < value.length()) {
            int remaining = value.codePointCount(offset, value.length());
            int count = Math.min(remaining, maximumCodePoints);
            int end = value.offsetByCodePoints(offset, count);
            result.add(value.substring(offset, end));
            offset = end;
        }
        return java.util.List.copyOf(result);
    }
}
