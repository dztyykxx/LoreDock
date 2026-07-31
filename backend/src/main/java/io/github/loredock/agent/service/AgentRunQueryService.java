package io.github.loredock.agent.service;

import io.github.loredock.agent.exception.AgentRunNotFoundException;
import io.github.loredock.agent.model.snapshot.AgentEventSnapshot;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.project.api.ProjectService;
import java.util.List;
import org.springframework.stereotype.Service;

/** 以运行数据库快照为事实来源，并在每次读取时复核操作者和项目仍可访问。 */
@Service
public class AgentRunQueryService {

    private static final int MAX_EVENT_PAGE_SIZE = 200;
    private final AgentRunService runs;
    private final AgentEventService events;
    private final ProjectService projects;

    /** @param runs 运行仓储 @param events 事件仓储 @param projects 启用项目范围查询 */
    public AgentRunQueryService(
            AgentRunService runs,
            AgentEventService events,
            ProjectService projects
    ) {
        this.runs = runs;
        this.events = events;
        this.projects = projects;
    }

    public AgentRunSnapshot get(Long runId, String operatorId) {
        AgentRunSnapshot snapshot = authorized(runId, operatorId);
        // 仓储返回的最新终态与引用是事实，不从内存事件反推。
        return snapshot;
    }

    public List<AgentEventSnapshot> list(Long runId, String operatorId, long afterSequence, int limit) {
        authorized(runId, operatorId);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence invalid");
        }
        int bounded = Math.min(Math.max(limit, 1), MAX_EVENT_PAGE_SIZE);
        return events.findAfter(runId, afterSequence, bounded);
    }

    public long lastSequence(Long runId, String operatorId) {
        authorized(runId, operatorId);
        return events.lastSequence(runId);
    }

    private AgentRunSnapshot authorized(Long runId, String operatorId) {
        if (runId == null || operatorId == null || operatorId.isBlank()) {
            throw new AgentRunNotFoundException();
        }
        AgentRunSnapshot snapshot = runs.findById(runId).orElseThrow(AgentRunNotFoundException::new);
        if (!snapshot.operatorId().equals(operatorId)) {
            throw new AgentRunNotFoundException();
        }
        try {
            projects.resolveEnabledScope(snapshot.scope().projectIdentifier(), snapshot.scope().branch());
        } catch (RuntimeException exception) {
            throw new AgentRunNotFoundException();
        }
        return snapshot;
    }
}
