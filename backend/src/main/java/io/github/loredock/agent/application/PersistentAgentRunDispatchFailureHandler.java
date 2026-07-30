package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.platform.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用独立短事务把队列拒绝或调度异常转成运行终态；比较更新保护已完成或已终止的运行不被覆盖。
 */
@Service
@Slf4j
public class PersistentAgentRunDispatchFailureHandler implements AgentRunDispatchFailureHandler {

    private final AgentRunRepository runs;
    private final AgentEventRepository events;
    private final TimeProvider timeProvider;

    /** @param runs 运行仓储 @param events 公开事件仓储 @param timeProvider UTC 时间源 */
    public PersistentAgentRunDispatchFailureHandler(
            AgentRunRepository runs,
            AgentEventRepository events,
            TimeProvider timeProvider
    ) {
        this.runs = runs;
        this.events = events;
        this.timeProvider = timeProvider;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID runId, AgentErrorCode errorCode) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(errorCode, "errorCode");
        Instant failedAt = timeProvider.now();
        boolean updated = runs.finishWithError(
                runId, errorCode, false, AgentExecutionUsage.none(), failedAt);
        if (updated) {
            events.append(runId, AgentEventType.RUN_FAILED, errorCode.name(), failedAt);
        } else {
            // 提交后回调可能迟到；终态优先，不能用调度失败覆盖已经完成的可信结果。
            log.warn("agent_run dispatch failure ignored runId={} errorCode={} reason=terminal_state",
                    runId, errorCode);
        }
        log.info("agent_run dispatch failure handled runId={} errorCode={} applied={}",
                runId, errorCode, updated);
    }
}
