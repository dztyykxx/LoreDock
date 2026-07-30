package io.github.loredock.agent.infrastructure.runtime;

import io.github.loredock.agent.application.AgentEventRepository;
import io.github.loredock.agent.application.AgentExecutionUsage;
import io.github.loredock.agent.application.AgentRunRepository;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.platform.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时单调终结上一进程遗留的非终态运行，T6A 不伪装检查点恢复。 */
@Component
@Slf4j
public class AgentRunRecovery implements ApplicationRunner {

    private final AgentRunRepository runs;
    private final AgentEventRepository events;
    private final TimeProvider timeProvider;

    /** @param runs 运行仓储 @param events 事件仓储 @param timeProvider UTC 时间源 */
    public AgentRunRecovery(AgentRunRepository runs, AgentEventRepository events, TimeProvider timeProvider) {
        this.runs = runs;
        this.events = events;
        this.timeProvider = timeProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = 0;
        for (var run : runs.findNonTerminalRuns()) {
            var now = timeProvider.now();
            if (runs.finishWithError(run.runId(), AgentErrorCode.AGENT_RUN_INTERRUPTED, true,
                    AgentExecutionUsage.none(), now)) {
                events.append(run.runId(), AgentEventType.RUN_TERMINATED,
                        AgentErrorCode.AGENT_RUN_INTERRUPTED.name(), now);
                recovered++;
            }
        }
        log.info("agent_run_recovery completed terminatedCount={}", recovered);
    }
}
