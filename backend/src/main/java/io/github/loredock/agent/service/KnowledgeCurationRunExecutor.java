package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.InterruptionHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 知识整理运行的薄执行接线：直接构建框架 ReactAgent、Task Tool、Hook 与 PostgresSaver，
 * 只负责既有 run 的状态和公开消息投影，不实现模型/Tool 循环、子 Agent 调度或 Checkpoint。
 */
@Service
public class KnowledgeCurationRunExecutor {

    private static final int MAX_FINAL_CODE_POINTS = 8000;
    private final ObjectProvider<ChatModel> models;
    private final AgentProperties properties;
    private final ToolCallbackProvider tools;
    private final com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry skills;
    private final PostgresSaver checkpoints;
    private final AgentRunMapper runs;
    private final KnowledgeTaskMessageMapper messages;
    private final AgentEventService events;
    private final KnowledgeTaskRunProjectionService projection;
    private final BoundedAgentRunScheduler scheduler;
    private final Clock clock;
    private final Map<Long, ReactAgent> active = new ConcurrentHashMap<>();

    /** 注入框架组件、既有运行持久化和共享有界调度器。 */
    public KnowledgeCurationRunExecutor(
            ObjectProvider<ChatModel> models,
            AgentProperties properties,
            ToolCallbackProvider tools,
            com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry skills,
            PostgresSaver checkpoints,
            AgentRunMapper runs,
            KnowledgeTaskMessageMapper messages,
            AgentEventService events,
            KnowledgeTaskRunProjectionService projection,
            BoundedAgentRunScheduler scheduler,
            Clock clock
    ) {
        this.models = models;
        this.properties = properties;
        this.tools = tools;
        this.skills = skills;
        this.checkpoints = checkpoints;
        this.runs = runs;
        this.messages = messages;
        this.events = events;
        this.projection = projection;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** @return 部署已显式启用且存在标准 ChatModel 时才会自动调度 */
    public boolean available() {
        return properties.enabled() && models.getIfAvailable() != null;
    }

    /** 提交新 run 到项目既有 Agent 有界执行器。 */
    public void start(AgentRunEntity run, String goal, KnowledgeAgentDefinitionService.LoadedDefinition definition) {
        if (!available()) {
            return;
        }
        if (!scheduler.schedule(run.getId(), () -> execute(run, goal, null, definition))) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_EXECUTOR_SATURATED");
        }
    }

    /** 框架在下一个 InterruptionHook 安全边界消费空反馈并提交 Checkpoint。 */
    public void requestPause(AgentRunEntity run) {
        ReactAgent agent = active.get(run.getId());
        if (agent != null) {
            agent.interrupt(config(run));
        }
    }

    /** 使用同一 run/threadId 和已提交 Checkpoint 恢复；进程重启时按原摘要校验后的定义重建 Agent。 */
    public void resume(
            AgentRunEntity run,
            String goal,
            String guidance,
            KnowledgeAgentDefinitionService.LoadedDefinition definition
    ) {
        if (!available()) {
            return;
        }
        if (!scheduler.schedule(run.getId(), () -> execute(run, goal, guidance, definition))) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_EXECUTOR_SATURATED");
        }
    }

    private void execute(
            AgentRunEntity run,
            String goal,
            String guidance,
            KnowledgeAgentDefinitionService.LoadedDefinition definition
    ) {
        Instant started = clock.instant();
        boolean resuming = guidance != null;
        if (!resuming && runs.markKnowledgeRunning(run.getId(), started) != 1) {
            return;
        }
        ReactAgent agent = active.computeIfAbsent(run.getId(), ignored -> build(run, goal, definition));
        RunnableConfig config = config(run);
        try {
            events.append(run.getId(), resuming ? AgentEventType.AGENT_STAGE : AgentEventType.RUN_STARTED,
                    AgentEvent.SubjectType.AGENT,
                    payload(resuming ? "RESUMING" : "PREPARING", run.getAgentName(), "RUNNING"), started);
            AssistantMessage result;
            if (resuming) {
                agent.interrupt(guidance, config);
                result = agent.call(Map.of(), config);
            } else {
                result = agent.call(goal, config);
            }
            AgentRunEntity current = runs.selectById(run.getId());
            if (current != null && "PAUSE_REQUESTED".equals(current.getStatus())
                    && projection.markWaitingAfterInterrupt(run.getId())) {
                return;
            }
            String text = bounded(result == null ? "知识整理运行已完成" : result.getText());
            Instant finished = clock.instant();
            messages.insert(KnowledgeTaskMessageEntity.builder()
                    .conversationId(run.getKnowledgeTaskConversationId()).runId(run.getId())
                    .role("COORDINATOR_AGENT").subjectName(run.getAgentName()).content(text).createdAt(finished).build());
            events.append(run.getId(), AgentEventType.RUN_COMPLETED, AgentEvent.SubjectType.AGENT,
                    payload("COMPLETED", run.getAgentName(), "COMPLETED"), finished);
            runs.completeKnowledge(run.getId(), text, 1, 1, 0,
                    Duration.between(started, finished).toMillis(), finished);
            active.remove(run.getId(), agent);
            System.out.println("知识整理模型最终原始响应：" + text);
        } catch (Exception exception) {
            AgentRunEntity current = runs.selectById(run.getId());
            if (current != null && "PAUSE_REQUESTED".equals(current.getStatus())
                    && projection.markWaitingAfterInterrupt(run.getId())) {
                return;
            }
            active.remove(run.getId(), agent);
            fail(run.getId(), started, "AGENT_MODEL_RESPONSE_INVALID");
        }
    }

    private ReactAgent build(
            AgentRunEntity run,
            String goal,
            KnowledgeAgentDefinitionService.LoadedDefinition definition
    ) {
        List<ToolCallback> callbacks = new ArrayList<>(List.of(tools.getToolCallbacks()));
        callbacks.addAll(definition.taskTools());
        return ReactAgent.builder()
                .name("knowledge-curation-" + run.getId())
                .instruction("目标：" + goal + "。使用 knowledge_curator Skill；修改草稿必须先读后改，正式发布由管理员完成。")
                .model(Objects.requireNonNull(models.getIfAvailable(), "知识整理模型不可用"))
                .tools(callbacks)
                .toolContext(Map.of(
                        "operatorId", run.getOperatorId(),
                        "projectIdentifier", run.getProjectIdentifier(),
                        "conversationId", run.getKnowledgeTaskConversationId(),
                        "runId", run.getId()))
                .hooks(
                        SkillsAgentHook.builder().skillRegistry(skills).autoReload(true).build(),
                        InterruptionHook.builder().build(),
                        HumanInTheLoopHook.builder().build())
                .saver(checkpoints)
                .releaseThread(false)
                .parallelToolExecution(false)
                .build();
    }

    private RunnableConfig config(AgentRunEntity run) {
        return RunnableConfig.builder().threadId(run.getThreadId()).build();
    }

    private AgentEvent.Payload payload(String phase, String name, String status) {
        return new AgentEvent.Payload(phase, name, null, null, null, null, null, status,
                List.of(), null, null, null, null, false, false);
    }

    private String bounded(String value) {
        String text = value == null || value.isBlank() ? "知识整理运行已完成" : value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= MAX_FINAL_CODE_POINTS ? text
                : text.substring(0, text.offsetByCodePoints(0, MAX_FINAL_CODE_POINTS));
    }

    private void fail(Long runId, Instant started, String code) {
        Instant finished = clock.instant();
        runs.failKnowledge(runId, code, Math.max(0, Duration.between(started, finished).toMillis()), finished);
    }
}
