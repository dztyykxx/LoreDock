package io.github.loredock.agent.scheduler;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.service.AgentRunService;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时只终结无法 Checkpoint 恢复的 project_qa 短运行。 */
@Component
@Slf4j
public class AgentRunRecovery implements ApplicationRunner {

    private final AgentRunService runs;
    private final Clock timeProvider;

    /** @param runs 运行服务 @param timeProvider UTC 时间源 */
    public AgentRunRecovery(AgentRunService runs, Clock timeProvider) {
        this.runs = runs;
        this.timeProvider = timeProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = 0;
        for (var run : runs.findNonTerminalProjectQaRuns()) {
            var now = timeProvider.instant();
            if (runs.finishWithError(run.runId(), AgentErrorCode.AGENT_RUN_INTERRUPTED, true,
                    AgentExecutionUsage.none(), now)) {
                recovered++;
            }
        }
        log.info("agent_run_recovery completed terminatedCount={}", recovered);
    }
}
