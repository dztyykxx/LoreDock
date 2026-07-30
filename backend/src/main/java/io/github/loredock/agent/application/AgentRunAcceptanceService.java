package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只负责在一个短事务中插入 ACCEPTED 运行与首条事件。 */
@Service
public class AgentRunAcceptanceService {

    private final AgentRunRepository runs;
    private final AgentEventRepository events;

    /** @param runs 运行仓储 @param events 公开事件仓储 */
    public AgentRunAcceptanceService(AgentRunRepository runs, AgentEventRepository events) {
        this.runs = runs;
        this.events = events;
    }

    /**
     * @return 事务内已写入的运行快照；方法返回后调度器才能开始执行
     */
    @Transactional
    public AgentRunSnapshot accept(AgentRunCreateData data) {
        runs.insert(data);
        events.append(data.runId(), AgentEventType.RUN_ACCEPTED, "accepted", data.acceptedAt());
        return runs.findById(data.runId()).orElseThrow(AgentRunNotFoundException::new);
    }
}
