package io.github.loredock.agent.application;

import io.github.loredock.project.application.ProjectQueryUseCase;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 以运行数据库快照为事实来源，并在每次读取时复核操作者和项目仍可访问。 */
@Service
public class AgentRunQueryService implements AgentRunQueryUseCase, AgentEventQueryUseCase {

    private static final int MAX_EVENT_PAGE_SIZE = 200;
    private final AgentRunRepository runs;
    private final AgentEventRepository events;
    private final ProjectQueryUseCase projects;

    /** @param runs 运行仓储 @param events 事件仓储 @param projects 启用项目范围查询 */
    public AgentRunQueryService(
            AgentRunRepository runs,
            AgentEventRepository events,
            ProjectQueryUseCase projects
    ) {
        this.runs = runs;
        this.events = events;
        this.projects = projects;
    }

    @Override
    public AgentRunSnapshot get(UUID runId, String operatorId) {
        AgentRunSnapshot snapshot = authorized(runId, operatorId);
        // 仓储返回的最新终态与引用是事实，不从内存事件反推。
        return snapshot;
    }

    @Override
    public List<AgentEventSnapshot> list(UUID runId, String operatorId, long afterSequence, int limit) {
        authorized(runId, operatorId);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence invalid");
        }
        int bounded = Math.min(Math.max(limit, 1), MAX_EVENT_PAGE_SIZE);
        return events.findAfter(runId, afterSequence, bounded);
    }

    private AgentRunSnapshot authorized(UUID runId, String operatorId) {
        if (runId == null || operatorId == null || operatorId.isBlank()) {
            throw new AgentRunNotFoundException();
        }
        AgentRunSnapshot snapshot = runs.findById(runId).orElseThrow(AgentRunNotFoundException::new);
        if (!snapshot.operatorId().equals(operatorId)) {
            throw new AgentRunNotFoundException();
        }
        try {
            projects.getEnabledProject(snapshot.scope().projectIdentifier(), snapshot.scope().branch());
        } catch (RuntimeException exception) {
            throw new AgentRunNotFoundException();
        }
        return snapshot;
    }
}
