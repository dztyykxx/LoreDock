package io.github.loredock.agent.service;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用独立短事务把队列拒绝或调度异常转成运行终态；比较更新保护已完成或已终止的运行不被覆盖。
 */
@Service
@Slf4j
public class PersistentAgentRunDispatchFailureHandler {

    private final AgentRunService runs;
    private final Clock timeProvider;

    /** @param runs 运行服务 @param timeProvider UTC 时间源 */
    public PersistentAgentRunDispatchFailureHandler(
            AgentRunService runs,
            Clock timeProvider
    ) {
        this.runs = runs;
        this.timeProvider = timeProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId, AgentErrorCode errorCode) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(errorCode, "errorCode");
        Instant failedAt = timeProvider.instant();
        boolean updated = runs.finishWithError(
                runId, errorCode, false, AgentExecutionUsage.none(), failedAt);
        if (!updated) {
            // 提交后回调可能迟到；终态优先，不能用调度失败覆盖已经完成的可信结果。
            log.warn("agent_run dispatch failure ignored runId={} errorCode={} reason=terminal_state",
                    runId, errorCode);
        }
        log.info("agent_run dispatch failure handled runId={} errorCode={} applied={}",
                runId, errorCode, updated);
    }
}
