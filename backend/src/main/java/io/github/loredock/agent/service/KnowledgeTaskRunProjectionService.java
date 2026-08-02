package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将框架 interrupt/Checkpoint 已提交事实投影到既有 `agent_run` 页面状态。
 * 该服务不执行 Agent、不保存 Graph 状态，也不允许在 Checkpoint 不可读时伪造等待人工。
 */
@Service
@Slf4j
public class KnowledgeTaskRunProjectionService {

    private final AgentRunMapper runs;
    private final PostgresSaver checkpoints;
    private final Clock clock;

    /** @param runs 既有运行 Mapper @param checkpoints 框架 Saver @param clock UTC 时间源 */
    public KnowledgeTaskRunProjectionService(AgentRunMapper runs, PostgresSaver checkpoints, Clock clock) {
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.clock = clock;
    }

    /**
     * 仅当稳定 threadId 的框架 Checkpoint 已可重新读取时，才把 PAUSE_REQUESTED 投影为 WAITING_FOR_USER。
     *
     * @param runId 知识任务运行
     * @return 是否成功投影
     */
    @Transactional
    public boolean markWaitingAfterInterrupt(Long runId) {
        AgentRunEntity run = Objects.requireNonNull(runs.selectById(runId), "知识任务运行不存在");
        if (run.getThreadId() == null || checkpoints.get(
                RunnableConfig.builder().threadId(run.getThreadId()).build()).isEmpty()) {
            log.warn("knowledge_task checkpoint unavailable conversationId={} runId={} status={}",
                    run.getKnowledgeTaskConversationId(), runId, run.getStatus());
            return false;
        }
        Instant savedAt = clock.instant();
        boolean updated = runs.markKnowledgeWaiting(runId, savedAt) == 1;
        log.info("knowledge_task checkpoint projected conversationId={} runId={} threadId={} "
                        + "from=PAUSE_REQUESTED to=WAITING_FOR_USER applied={}",
                run.getKnowledgeTaskConversationId(), runId, run.getThreadId(), updated);
        return updated;
    }
}
