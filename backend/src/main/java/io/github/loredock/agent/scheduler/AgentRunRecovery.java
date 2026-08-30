package io.github.loredock.agent.scheduler;

import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.service.AgentRunService;
import io.github.loredock.agent.service.KnowledgeAgentDefinitionService;
import io.github.loredock.agent.service.KnowledgeCurationRunExecutor;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时按任务类型执行不同的重启语义：
 * <ul>
 *   <li>project_qa 是短运行，无 Checkpoint 可恢复，直接以中断标记终结；</li>
 *   <li>知识整理是可恢复长运行：非终态 run 按已校验定义重建 Graph，从原 Checkpoint 的下一节点继续；
 *       定义摘要或 Graph 定义版本与当前不一致时由 Executor 停在定义不匹配终态，不解析旧 Checkpoint。</li>
 * </ul>
 */
@Component
@Slf4j
public class AgentRunRecovery implements ApplicationRunner {

    private final AgentRunService runs;
    private final KnowledgeAgentDefinitionService definitions;
    private final KnowledgeCurationRunExecutor executor;
    private final io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper conversations;
    private final Clock timeProvider;

    /** @param runs 运行服务 @param definitions 知识整理定义装载 @param executor 知识整理执行器
     * @param conversations 会话查询（恢复时取原目标） @param timeProvider UTC 时间源 */
    public AgentRunRecovery(
            AgentRunService runs,
            KnowledgeAgentDefinitionService definitions,
            KnowledgeCurationRunExecutor executor,
            io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper conversations,
            Clock timeProvider
    ) {
        this.runs = runs;
        this.definitions = definitions;
        this.executor = executor;
        this.conversations = conversations;
        this.timeProvider = timeProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        int qaRecovered = 0;
        for (var run : runs.findNonTerminalProjectQaRuns()) {
            var now = timeProvider.instant();
            if (runs.finishWithError(run.runId(), AgentErrorCode.AGENT_RUN_INTERRUPTED, true,
                    AgentExecutionUsage.none(), now)) {
                qaRecovered++;
            }
        }
        int curationScheduled = 0;
        for (AgentRunEntity run : runs.findRecoverableKnowledgeCurationRuns()) {
            // 知识整理：有 Checkpoint 的非终态 run 重新调度；无定义摘要的历史 run 由 Executor 继续或停止。
            try {
                KnowledgeAgentDefinitionService.LoadedDefinition definition =
                        definitions.load(run.getAgentName());
                String goal = goalOf(run.getKnowledgeTaskConversationId());
                executor.recover(run, goal, definition);
                curationScheduled++;
            } catch (Exception exception) {
                var now = timeProvider.instant();
                runs.finishWithError(run.getId(), AgentErrorCode.AGENT_DEFINITION_MISMATCH, false,
                        AgentExecutionUsage.none(), now);
                log.warn("agent_run recovery skip runId={} reason=curation_definition_unavailable",
                        run.getId(), exception);
            }
        }
        log.info("agent_run_recovery completed terminatedProjectQaRuns={} scheduledCurationRuns={}",
                qaRecovered, curationScheduled);
    }

    /** @return 会话原始目标；会话不存在时返回空串，交由 Executor 直接走 Checkpoint 恢复路径。 */
    private String goalOf(Long conversationId) {
        if (conversationId == null) {
            return "";
        }
        KnowledgeTaskConversationEntity conversation = conversations.selectById(conversationId);
        return conversation == null ? "" : conversation.getGoal();
    }
}
