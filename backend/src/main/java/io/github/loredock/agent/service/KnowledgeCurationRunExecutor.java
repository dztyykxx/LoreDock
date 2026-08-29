package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.hook.InterruptionHook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.StreamingModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.result.KnowledgeCurationGraphResult;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import io.github.loredock.knowledge.api.KnowledgeDraftException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 知识整理多 Agent Graph 的运行接线：使用 {@code KnowledgeCurationGraphFactory} 组装四个专家
 * {@code ReactAgent} 与父 {@code CompiledGraph}，用同一 run {@code threadId} 驱动，并在每个
 * {@code interruptAfter} 边界检查 run 状态（RUNNING 续跑 / PAUSE_REQUESTED 投影等待人工 /
 * CANCELLED 结束）。它只负责既有 run 生命周期与公开消息/阶段事件投影，不实现模型/Tool 循环、
 * 子 Agent 调度或通用工作流引擎。
 *
 * <p>四个 Agent 共享同一 run 级模型与 Tool Interceptor（统一原子计数），不新增每个 Agent 的独立配额配置；
 * 总超时按 run 首次开始时间计算，不因分段续跑重新计时。</p>
 */
@Service
@Slf4j
public class KnowledgeCurationRunExecutor {

    private static final int MAX_FINAL_CODE_POINTS = 8000;
    private static final int MAX_PUBLIC_PROGRESS_CODE_POINTS = 1000;
    private final ObjectProvider<ChatModel> models;
    private final AgentProperties properties;
    private final ToolCallbackResolver toolResolver;
    private final PostgresSaver checkpoints;
    private final KnowledgeAgentDefinitionService definitions;
    private final ObjectMapper objectMapper;
    private final AgentRunMapper runs;
    private final KnowledgeTaskConversationMapper conversations;
    private final KnowledgeTaskMessageMapper messages;
    private final AgentEventService events;
    private final KnowledgeTaskEventService taskEvents;
    private final KnowledgeToolInvocationService toolInvocations;
    private final KnowledgeTaskRunProjectionService projection;
    private final BoundedAgentRunScheduler scheduler;
    private final Clock clock;
    private final Map<Long, RunningRun> active = new ConcurrentHashMap<>();

    /** 注入框架组件、多 Agent 定义、既有运行持久化和共享有界调度器。 */
    public KnowledgeCurationRunExecutor(
            ObjectProvider<ChatModel> models,
            AgentProperties properties,
            ToolCallbackResolver toolResolver,
            PostgresSaver checkpoints,
            KnowledgeAgentDefinitionService definitions,
            ObjectMapper objectMapper,
            AgentRunMapper runs,
            KnowledgeTaskConversationMapper conversations,
            KnowledgeTaskMessageMapper messages,
            AgentEventService events,
            KnowledgeTaskEventService taskEvents,
            KnowledgeToolInvocationService toolInvocations,
            KnowledgeTaskRunProjectionService projection,
            BoundedAgentRunScheduler scheduler,
            Clock clock
    ) {
        this.models = models;
        this.properties = properties;
        this.toolResolver = toolResolver;
        this.checkpoints = checkpoints;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
        this.runs = runs;
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
        this.taskEvents = taskEvents;
        this.toolInvocations = toolInvocations;
        this.projection = projection;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** @return 部署已显式启用且存在标准 ChatModel 与已校验多 Agent 定义时才会自动调度 */
    public boolean available() {
        return properties.enabled() && models.getIfAvailable() != null && definitions.graphSpecs() != null;
    }

    /** 提交新 run 到项目既有 Agent 有界执行器。 */
    public void start(AgentRunEntity run, String goal, KnowledgeAgentDefinitionService.LoadedDefinition definition) {
        if (!available()) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_MODEL_UNAVAILABLE", 0, 0);
            return;
        }
        if (!scheduler.schedule(run.getId(), () -> execute(run, goal, null, definition))) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_EXECUTOR_SATURATED", 0, 0);
        }
    }

    /** 暂停在下一个 Graph {@code interruptAfter} 边界生效；由 run 状态驱动，不维护第二套状态机。 */
    public void requestPause(AgentRunEntity run) {
        log.info("knowledge_task pause requested, apply at next graph boundary conversationId={} runId={}",
                run.getKnowledgeTaskConversationId(), run.getId());
    }

    /** 中断当前框架执行；数据库 CANCELLED 终态由入口事务先提交，在下一个 Graph 边界确认。 */
    public void stop(AgentRunEntity run) {
        log.info("knowledge_task stop requested, confirm at next graph boundary conversationId={} runId={}",
                run.getKnowledgeTaskConversationId(), run.getId());
    }

    /** 使用同一 run/threadId 和已提交 Checkpoint 恢复；进程重启时按已校验定义重建 Graph。 */
    public void resume(
            AgentRunEntity run,
            String goal,
            String guidance,
            KnowledgeAgentDefinitionService.LoadedDefinition definition
    ) {
        if (!available()) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_MODEL_UNAVAILABLE", 0, 0);
            return;
        }
        if (!scheduler.schedule(run.getId(), () -> execute(run, goal, guidance, definition))) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_EXECUTOR_SATURATED", 0, 0);
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
        RunningRun running = active.computeIfAbsent(run.getId(), ignored -> build(run));
        CompiledGraph graph = running.graph();
        RunMetrics metrics = running.metrics();
        try {
            events.append(run.getId(), resuming ? AgentEventType.AGENT_STAGE : AgentEventType.RUN_STARTED,
                    AgentEvent.SubjectType.AGENT,
                    payload(resuming ? "RESUMING" : "PREPARING", run.getAgentName(), "RUNNING"), started);
            taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "RUN_UPDATED", run.getId(), started);
            String finalReply = drive(run, graph, metrics, goal, guidance);
            Instant finished = clock.instant();
            log.info("知识整理 Graph 完成 conversationId={} runId={} 最终回复={} 模型调用={} 工具调用={}",
                    run.getKnowledgeTaskConversationId(), run.getId(),
                    bounded(finalReply, MAX_FINAL_CODE_POINTS), metrics.modelCalls(), metrics.toolCalls());
            KnowledgeTaskMessageEntity finalMessage = KnowledgeTaskMessageEntity.builder()
                    .conversationId(run.getKnowledgeTaskConversationId()).runId(run.getId())
                    .role("COORDINATOR_AGENT").subjectName(run.getAgentName()).content(finalReply).createdAt(finished).build();
            messages.insert(finalMessage);
            taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "MESSAGE_CREATED",
                    finalMessage.getId(), finished);
            events.append(run.getId(), AgentEventType.RUN_COMPLETED, AgentEvent.SubjectType.AGENT,
                    payload("COMPLETED", run.getAgentName(), "COMPLETED"), finished);
            runs.completeKnowledge(run.getId(), finalReply, metrics.steps(), metrics.modelCalls(), metrics.toolCalls(),
                    Duration.between(started, finished).toMillis(), metrics.inputTokens(), metrics.outputTokens(),
                    finished);
            taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "RUN_UPDATED", run.getId(), finished);
            active.remove(run.getId(), running);
            System.out.println("知识整理模型最终原始响应：" + finalReply);
        } catch (Exception exception) {
            AgentRunEntity current = runs.selectById(run.getId());
            if (current != null && "CANCELLED".equals(current.getStatus())) {
                active.remove(run.getId(), running);
                return;
            }
            if (current != null && "PAUSE_REQUESTED".equals(current.getStatus())
                    && projection.markWaitingAfterInterrupt(run.getId())) {
                return;
            }
            active.remove(run.getId(), running);
            String code = errorCode(exception);
            events.append(run.getId(), AgentEventType.RUN_FAILED, AgentEvent.SubjectType.AGENT,
                    failurePayload(run.getAgentName(), code), clock.instant());
            fail(run.getId(), started, code, metrics.modelCalls(), metrics.toolCalls());
            taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "RUN_UPDATED",
                    run.getId(), clock.instant());
            log.warn("knowledge_task failed conversationId={} runId={} errorCode={} modelCalls={} toolCalls={}",
                    run.getKnowledgeTaskConversationId(), run.getId(), code,
                    metrics.modelCalls(), metrics.toolCalls(), exception);
        }
    }

    /**
     * 驱动父 Graph 到终态：每次运行到 {@code interruptAfter} 边界先检查 run 状态，未暂停则用同一
     * {@code threadId} 立即续跑；返回调度 Agent 的最终可见正文（CHAT 或 FINISH 的 summary）。
     *
     * @param guidance 暂停恢复时管理员追加的指导；恢复的同一次 run 必须把它作为本轮用户消息注入，
     *                 否则协调 Agent 只会看到最开始的 goal，无法理解后续指令（continue 走 continuationPrompt，
     *                 而 resume 走此处的显式注入）
     */
    private String drive(AgentRunEntity run, CompiledGraph graph, RunMetrics metrics, String goal, String guidance) {
        Map<String, Object> input = new HashMap<>();
        input.put("goal", goal);
        input.put("stage", "START");
        input.put("draftRound", 0);
        if (guidance != null && !guidance.isBlank()) {
            input.put("messages", List.of(new UserMessage(goal),
                    new UserMessage("管理员追加指导：" + guidance)));
        } else {
            input.put("messages", List.of(new UserMessage(goal)));
        }
        // 首次用 threadId 配置；续跑必须用上一节点运行返回的 checkpoint 配置（含 checkPointId/nextNode），
        // 否则框架会从头重跑入口节点而不是从下一节点恢复（§8.3）。
        RunnableConfig resumeConfig = config(run);
        log.info("知识整理 Graph 运行开始 conversationId={} runId={} threadId={} goal={} stage=START draftRound=0",
                run.getKnowledgeTaskConversationId(), run.getId(), run.getThreadId(), bounded(goal, 200));
        int rounds = 0;
        while (rounds++ < 32) {
            consume(graph.stream(input, resumeConfig), run, metrics);
            AgentRunEntity current = runs.selectById(run.getId());
            if (current != null && "CANCELLED".equals(current.getStatus())) {
                throw new IllegalStateException("Agent cancelled");
            }
            if (current != null && "PAUSE_REQUESTED".equals(current.getStatus())
                    && projection.markWaitingAfterInterrupt(run.getId())) {
                throw new IllegalStateException("Agent paused");
            }
            StateSnapshot snapshot = graph.getState(config(run));
            String next = snapshot == null ? null : snapshot.next();
            log.info("知识整理 Graph 已到 interruptAfter 边界 conversationId={} runId={} 迭代={} 下一节点={} "
                            + "阶段={} 草稿轮数={} 已产出结果键={}",
                    run.getKnowledgeTaskConversationId(), run.getId(), rounds - 1, next,
                    stateText(snapshot, "stage"), stateInt(snapshot, "draftRound"), presentResultKeys(snapshot));
            if (next == null || StateGraph.END.equals(next)) {
                return finalReply(snapshot);
            }
            input = Map.of();
            resumeConfig = snapshot.config();
        }
        throw new IllegalStateException("Agent graph exceeded resume rounds");
    }

    private void consume(Flux<NodeOutput> stream, AgentRunEntity run, RunMetrics metrics) {
        stream.doOnNext(output -> {
                    if (output instanceof StreamingOutput<?> streaming
                            && streaming.getOutputType() == OutputType.AGENT_MODEL_FINISHED) {
                        persistAgentResult(run, output, streaming, metrics);
                    }
                })
                .timeout(properties.totalTimeout())
                .then()
                .block();
    }

    /** 每个专家 Agent 完成时把结构化结果投影为公开消息：调度 Agent 阶段性说明为进度，三个专家结果为 SUB_AGENT。 */
    private void persistAgentResult(
            AgentRunEntity run, NodeOutput output, StreamingOutput<?> streaming, RunMetrics metrics) {
        if (!(streaming.getOriginData() instanceof ChatResponse response)
                || response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage message = response.getResult().getOutput();
        if (message.getText() == null || message.getText().isBlank()) {
            return;
        }
        // 子 Agent 作为 subgraph 节点时 agent() 形如 subgraph_coordinator，node() 是框架内部 _AGENT_MODEL_；
        // 去掉 subgraph_ 前缀得到设计规定的稳定角色名 coordinator/retriever/drafter/reviewer。
        String node = output.agent() == null || output.agent().isBlank() ? output.node() : output.agent();
        if (node.startsWith("subgraph_")) {
            node = node.substring("subgraph_".length());
        }
        String phase = stagePhase(node, message.getText());
        log.info("知识整理 Agent 节点完成 conversationId={} runId={} node={} phase={} 公开摘要={}",
                run.getKnowledgeTaskConversationId(), run.getId(), node, phase,
                bounded(projectSummary(node, message.getText()), MAX_PUBLIC_PROGRESS_CODE_POINTS));
        persistStageEvent(run, node, message, metrics);
        if (KnowledgeCurationGraphFactory.COORDINATOR.equals(node)) {
            String stage = stageOf(message.getText());
            if (!"FINISH".equals(stage) && !isChatReply(message.getText())) {
                persistCoordinatorProgress(run, message);
            }
        } else if (isExpert(node)) {
            persistSubAgent(run, node, message);
        }
    }

    /** 每次专家节点完成时写入一条 {@code AGENT_STAGE} 公开事件，并追加 {@code AGENT_STAGE_UPDATED} 刷新通知。 */
    private void persistStageEvent(
            AgentRunEntity run, String node, AssistantMessage message, RunMetrics metrics) {
        String phase = stagePhase(node, message.getText());
        String summary = bounded(projectSummary(node, message.getText()), MAX_PUBLIC_PROGRESS_CODE_POINTS);
        RunMetrics.AgentToken tokens = metrics.takeStageTokens(node);
        Instant now = clock.instant();
        events.append(run.getId(), AgentEventType.AGENT_STAGE, AgentEvent.SubjectType.AGENT,
                new AgentEvent.Payload(phase, node, null, null, null, null, null, "COMPLETED",
                        List.of(), summary, null, null, null, false, false,
                        tokens.promptTokens(), tokens.completionTokens()),
                now);
        taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "AGENT_STAGE_UPDATED",
                run.getId(), now);
    }

    /** 阶段事件 phase：调度 Agent 按 stage（START/DECIDE/FINISH），三个专家按 RETRIEVE/DRAFT/REVIEW。 */
    private String stagePhase(String node, String text) {
        if (!KnowledgeCurationGraphFactory.COORDINATOR.equals(node)) {
            return switch (node) {
                case KnowledgeCurationGraphFactory.RETRIEVER -> "RETRIEVE";
                case KnowledgeCurationGraphFactory.DRAFTER -> "DRAFT";
                case KnowledgeCurationGraphFactory.REVIEWER -> "REVIEW";
                default -> "AGENT";
            };
        }
        return stageOf(text);
    }

    private void persistCoordinatorProgress(AgentRunEntity run, AssistantMessage message) {
        String progress = bounded(message.getText(), MAX_PUBLIC_PROGRESS_CODE_POINTS);
        if (progress == null || progress.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        KnowledgeTaskMessageEntity entity = KnowledgeTaskMessageEntity.builder()
                .conversationId(run.getKnowledgeTaskConversationId()).runId(run.getId())
                .role("COORDINATOR_AGENT").subjectName("公开行动摘要")
                .content(progress).createdAt(now).build();
        RunningRun running = active.get(run.getId());
        if (running != null && !running.publicProgress().add(progress)) {
            return;
        }
        messages.insert(entity);
        taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "MESSAGE_CREATED", entity.getId(), now);
        conversations.update(null, Wrappers.<KnowledgeTaskConversationEntity>lambdaUpdate()
                .set(KnowledgeTaskConversationEntity::getUpdatedAt, now)
                .eq(KnowledgeTaskConversationEntity::getId, run.getKnowledgeTaskConversationId()));
    }

    private void persistSubAgent(AgentRunEntity run, String node, AssistantMessage message) {
        String subject = switch (node) {
            case KnowledgeCurationGraphFactory.RETRIEVER -> "检索 Agent";
            case KnowledgeCurationGraphFactory.DRAFTER -> "草稿 Agent";
            case KnowledgeCurationGraphFactory.REVIEWER -> "审查 Agent";
            default -> node;
        };
        String summary = bounded(projectSummary(node, message.getText()), MAX_PUBLIC_PROGRESS_CODE_POINTS);
        if (summary == null || summary.isBlank()) {
            return;
        }
        KnowledgeTaskMessageEntity entity = KnowledgeTaskMessageEntity.builder()
                .conversationId(run.getKnowledgeTaskConversationId()).runId(run.getId())
                .role("SUB_AGENT").subjectName(subject).content(summary).createdAt(clock.instant()).build();
        messages.insert(entity);
        taskEvents.append(run.getKnowledgeTaskConversationId(), run.getId(), "MESSAGE_CREATED",
                entity.getId(), clock.instant());
    }

    private String finalReply(StateSnapshot snapshot) {
        if (snapshot == null || snapshot.state() == null) {
            throw new IllegalStateException("Agent completed without graph state");
        }
        Object coordination = snapshot.state().data().get("coordinationResult");
        String text = coordination instanceof AssistantMessage message ? message.getText()
                : coordination == null ? null : String.valueOf(coordination);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Agent completed without a visible final AssistantMessage");
        }
        try {
            KnowledgeCurationGraphResult.CoordinatorResult result =
                    objectMapper.readValue(text, KnowledgeCurationGraphResult.CoordinatorResult.class);
            if (result.summary() == null || result.summary().isBlank()) {
                throw new IllegalStateException("Agent final reply blank");
            }
            return bounded(result.summary(), MAX_FINAL_CODE_POINTS);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent final structured result invalid", exception);
        }
    }

    private RunningRun build(AgentRunEntity run) {
        RunMetrics metrics = new RunMetrics(run.getId(), run.getKnowledgeTaskConversationId());
        ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
                .runLimit(properties.limits().curationMaxModelCalls())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
        ToolCallLimitHook toolLimit = ToolCallLimitHook.builder()
                .runLimit(properties.limits().curationMaxToolCalls())
                .exitBehavior(ToolCallLimitHook.ExitBehavior.ERROR)
                .build();
        try {
            KnowledgeCurationGraphFactory.GraphBundle bundle = new KnowledgeCurationGraphFactory(objectMapper).build(
                    definitions.graphSpecs(),
                    Objects.requireNonNull(models.getIfAvailable(), "知识整理模型不可用"),
                    toolResolver,
                    Map.of(
                            "operatorId", run.getOperatorId(),
                            "projectIdentifier", run.getProjectIdentifier(),
                            "conversationId", run.getKnowledgeTaskConversationId(),
                            "runId", run.getId()),
                    checkpoints,
                    List.of(InterruptionHook.builder().build(), modelLimit, toolLimit),
                    List.of(metrics, metrics.toolInterceptor()),
                    toolExceptionProcessor());
            return new RunningRun(bundle.graph(), metrics, ConcurrentHashMap.newKeySet());
        } catch (GraphStateException exception) {
            throw new IllegalStateException("知识整理 Graph 组装失败", exception);
        }
    }

    private String stageOf(String text) {
        if (text == null) {
            return "";
        }
        if (text.contains("\"stage\":\"FINISH\"")) {
            return "FINISH";
        }
        if (text.contains("\"stage\":\"DECIDE\"")) {
            return "DECIDE";
        }
        return "START";
    }

    /** @return Graph Checkpoint 快照中的指定状态键，缺失或快照为空时返回 null。 */
    private static String stateText(StateSnapshot snapshot, String key) {
        if (snapshot == null || snapshot.state() == null) {
            return null;
        }
        Object value = snapshot.state().data().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** @return Graph Checkpoint 快照中的整数状态键，缺失或不可解析时返回 0。 */
    private static int stateInt(StateSnapshot snapshot, String key) {
        Object value = snapshot == null || snapshot.state() == null ? null : snapshot.state().data().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** @return 已写入 Checkpoint 的专家结果键集合，便于观察哪些节点已到达终态输出。 */
    private static String presentResultKeys(StateSnapshot snapshot) {
        if (snapshot == null || snapshot.state() == null) {
            return "[]";
        }
        Map<String, Object> data = snapshot.state().data();
        return List.of("coordinationResult", "retrievalResult", "draftResult", "reviewResult", "finishReason")
                .stream().filter(data::containsKey).toList().toString();
    }

    private boolean isExpert(String node) {
        return KnowledgeCurationGraphFactory.RETRIEVER.equals(node)
                || KnowledgeCurationGraphFactory.DRAFTER.equals(node)
                || KnowledgeCurationGraphFactory.REVIEWER.equals(node);
    }

    private boolean isChatReply(String text) {
        return text.contains("\"action\":\"CHAT\"");
    }

    private String projectSummary(String node, String text) {
        try {
            if (KnowledgeCurationGraphFactory.COORDINATOR.equals(node)) {
                return objectMapper.readValue(text, KnowledgeCurationGraphResult.CoordinatorResult.class).summary();
            }
            if (KnowledgeCurationGraphFactory.RETRIEVER.equals(node)) {
                return objectMapper.readValue(text, KnowledgeCurationGraphResult.RetrievalResult.class).summary();
            }
            if (KnowledgeCurationGraphFactory.DRAFTER.equals(node)) {
                return objectMapper.readValue(text, KnowledgeCurationGraphResult.DraftResult.class).summary();
            }
            if (KnowledgeCurationGraphFactory.REVIEWER.equals(node)) {
                return objectMapper.readValue(text, KnowledgeCurationGraphResult.ReviewResult.class).summary();
            }
        } catch (Exception ignore) {
            // 结构化结果无效时以全文截断展示，避免丢失模型结论。
        }
        return text;
    }

    /** 框架 Interceptor 记录真实经过模型与 Tool 节点的次数与 token 用量，不推测调用数量。 */
    private final class RunMetrics extends ModelInterceptor implements StreamingModelInterceptor {
        private static final int MAX_TOOL_PREVIEW_CODE_POINTS = 500;
        private final Long runId;
        private final Long conversationId;
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicBoolean usageComplete = new AtomicBoolean(true);
        private final Map<String, AgentToken> tokensByAgent = new ConcurrentHashMap<>();
        /** 各 Agent 已随 AGENT_STAGE 事件提交过的累计水位，用于阶段事件只报“本次增量”而非累计值。 */
        private final Map<String, AgentToken> committedTokensByAgent = new ConcurrentHashMap<>();
        private final AtomicReference<Usage> streamUsage = new AtomicReference<>();

        private RunMetrics(Long runId, Long conversationId) {
            this.runId = runId;
            this.conversationId = conversationId;
        }

        @Override
        public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
            modelCalls.incrementAndGet();
            return handler.call(request);
        }

        @Override
        public ModelRequest beforeStreamCall(ModelRequest request) {
            streamUsage.set(null);
            return request;
        }

        // 流式响应可能只在最后一个分片携带 usage，此处先缓存到 streamUsage，待流结束的 afterStreamComplete 统一累计。
        @Override
        public ChatResponse onStreamChunk(ChatResponse chunk, ModelRequest request) {
            if (chunk != null && chunk.getMetadata().getUsage() != null) {
                streamUsage.set(chunk.getMetadata().getUsage());
            }
            return chunk;
        }

        @Override
        public void afterStreamComplete(AssistantMessage aggregatedMessage, ModelRequest request) {
            record(streamUsage.getAndSet(null), request);
        }

        /** 按当前 Agent 归属累计该次模型调用的输入/输出 token；缺失 usage 时视为用量未知，整 run 报 null。 */
        private void record(Usage usage, ModelRequest request) {
            if (usage == null || usage instanceof EmptyUsage
                    || usage.getPromptTokens() == null || usage.getCompletionTokens() == null) {
                usageComplete.set(false);
                return;
            }
            int prompt = usage.getPromptTokens();
            int completion = usage.getCompletionTokens();
            inputTokens.addAndGet(prompt);
            outputTokens.addAndGet(completion);
            // _AGENT_ 元数据缺失时只累计 run 级总量，避免并发 Map 空键 NPE 且不产生无归属展示项。
            String agent = agentNode(request);
            if (agent != null) {
                tokensByAgent.computeIfAbsent(agent, ignored -> new AgentToken()).add(prompt, completion);
            }
        }

        @Override
        public String getName() {
            return "knowledgeRunModelMetrics";
        }

        private ToolInterceptor toolInterceptor() {
            return new ToolInterceptor() {
                @Override
                public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                    toolCalls.incrementAndGet();
                    String toolName = request.getToolName();
                    String purpose = toolPurpose(toolName);
                    Instant started = clock.instant();
                    toolInvocations.start(conversationId, runId, request.getToolCallId(), toolName,
                            agentNode(request), purpose, request.getArguments(), started);
                    events.append(runId, AgentEventType.TOOL_STARTED, AgentEvent.SubjectType.TOOL,
                            toolPayload(toolName, purpose, null, null, "STARTED", false), started);
                    try {
                        ToolCallResponse response = handler.call(request);
                        long durationMillis = Duration.between(started, clock.instant()).toMillis();
                        boolean failed = response.isError() || (response.getResult() != null
                                && response.getResult().startsWith("TOOL_ERROR: "));
                        String preview = preview(response.getResult());
                        Instant finished = clock.instant();
                        toolInvocations.finish(conversationId, runId, request.getToolCallId(),
                                response.getResult(), safeResultSummary(toolName, failed), failed, finished);
                        events.append(runId, AgentEventType.TOOL_COMPLETED, AgentEvent.SubjectType.TOOL,
                                toolPayload(toolName, purpose, safeResultSummary(toolName, failed),
                                        durationMillis, failed ? "FAILED" : "COMPLETED", false),
                                finished);
                        System.out.println("知识整理工具调用：tool=" + toolName + "，结果预览=" + preview);
                        return response;
                    } catch (RuntimeException exception) {
                        Instant finished = clock.instant();
                        long durationMillis = Duration.between(started, finished).toMillis();
                        toolInvocations.finish(conversationId, runId, request.getToolCallId(),
                                exception.getMessage(), "工具执行失败", true, finished);
                        events.append(runId, AgentEventType.TOOL_COMPLETED, AgentEvent.SubjectType.TOOL,
                                toolPayload(toolName, purpose, "工具执行失败", durationMillis, "FAILED", false),
                                finished);
                        throw exception;
                    }
                }

                @Override
                public String getName() {
                    return "knowledgeRunToolMetrics";
                }
            };
        }

        private int modelCalls() {
            return modelCalls.get();
        }

        private int toolCalls() {
            return toolCalls.get();
        }

        private int steps() {
            return modelCalls() + toolCalls();
        }

        /** @return 整轮 run 的输入 token 总数；任一模型调用缺失 usage 时返回 null（避免部分汇总被当作完整值）。 */
        private Long inputTokens() {
            return usageComplete.get() ? inputTokens.get() : null;
        }

        /** @return 整轮 run 的输出 token 总数；任一模型调用缺失 usage 时返回 null。 */
        private Long outputTokens() {
            return usageComplete.get() ? outputTokens.get() : null;
        }

        /**
         * @return 指定 Agent 本次阶段新增的 token（相对上次阶段事件的增量），并把该 Agent 的提交水位推进到当前累计。
         *         同一 Agent 多次进入（调度 START/DECIDE/FINISH、返工）时，各阶段事件只报自身消耗，
         *         保证公开事件按阶段相加等于 run 级总量，不会因累计值重复计入使前端“总和对不上”。
         */
        private AgentToken takeStageTokens(String agent) {
            AgentToken current = tokensByAgent.getOrDefault(agent, AgentToken.EMPTY);
            AgentToken committed = committedTokensByAgent.getOrDefault(agent, AgentToken.EMPTY);
            AgentToken delta = new AgentToken();
            delta.add(current.prompt.intValue() - committed.prompt.intValue(),
                    current.completion.intValue() - committed.completion.intValue());
            // 必须存副本：直接存 current 引用会与 tokensByAgent 共享同一对象，后续累计同时改动“水位”，delta 恒为 0。
            committedTokensByAgent.put(agent, current.copy());
            return delta;
        }

        /** 单个 Agent 的累计输入/输出 token。 */
        private static final class AgentToken {
            private static final AgentToken EMPTY = new AgentToken();
            private final AtomicLong prompt = new AtomicLong();
            private final AtomicLong completion = new AtomicLong();

            private void add(int promptTokens, int completionTokens) {
                prompt.addAndGet(promptTokens);
                completion.addAndGet(completionTokens);
            }

            /** @return 当前值的独立快照，避免与累计 Map 共享同一可变对象。 */
            private AgentToken copy() {
                AgentToken token = new AgentToken();
                token.add(prompt.intValue(), completion.intValue());
                return token;
            }

            private Integer promptTokens() {
                return prompt.intValue();
            }

            private Integer completionTokens() {
                return completion.intValue();
            }
        }

        private AgentEvent.Payload toolPayload(
                String name,
                String purpose,
                String resultSummary,
                Long durationMillis,
                String status,
                boolean truncated
        ) {
            return new AgentEvent.Payload("EXECUTING", name, purpose, null, resultSummary, null,
                    durationMillis, status, List.of(), null, null, null, null, false, truncated);
        }

        private String preview(String value) {
            String text = value == null || value.isBlank() ? "无返回内容" : value.strip();
            int count = text.codePointCount(0, text.length());
            return count <= MAX_TOOL_PREVIEW_CODE_POINTS ? text
                    : text.substring(0, text.offsetByCodePoints(0, MAX_TOOL_PREVIEW_CODE_POINTS)) + "…";
        }

        private String safeResultSummary(String toolName, boolean failed) {
            if (failed) {
                return "工具返回失败状态";
            }
            return switch (toolName) {
                case "selected_draft_list" -> "已读取本次固定输入清单";
                case "selected_draft_read" -> "已读取一份固定输入草稿";
                case "knowledge_directory_list" -> "已读取知识库目录";
                case "knowledge_document_list" -> "已读取目录下文档清单";
                case "knowledge_document_read" -> "已读取相关文档分段";
                case "knowledge_grep" -> "已完成关键词匹配";
                case "knowledge_search" -> "已完成近似文档检索";
                case "workspace_document_list" -> "已恢复当前工作区文档";
                case "draft_create" -> "已创建工作文档";
                case "draft_rename" -> "已更正工作文档标题";
                case "draft_read" -> "已读取工作文档";
                case "draft_update" -> "已生成工作文档新修订";
                case "draft_diff" -> "已生成待审核 Diff";
                default -> "工具执行完成";
            };
        }

        private String toolPurpose(String toolName) {
            return switch (toolName) {
                case "selected_draft_list", "selected_draft_read" -> "读取选中的待处理草稿";
                case "knowledge_directory_list", "knowledge_document_list" -> "浏览现有知识库";
                case "knowledge_document_read" -> "分段核对现有文档";
                case "knowledge_grep", "knowledge_search" -> "检索相关业务知识";
                case "workspace_document_list" -> "恢复多文档工作区";
                case "draft_create", "draft_read", "draft_rename", "draft_update" -> "生成或修改工作文档";
                case "draft_diff" -> "生成审核差异";
                default -> "执行知识整理步骤";
            };
        }
    }

    private record RunningRun(CompiledGraph graph, RunMetrics metrics, Set<String> publicProgress) {
    }

    private RunnableConfig config(AgentRunEntity run) {
        return RunnableConfig.builder().threadId(run.getThreadId()).build();
    }

    private AgentEvent.Payload payload(String phase, String name, String status) {
        return new AgentEvent.Payload(phase, name, null, null, null, null, null, status,
                List.of(), null, null, null, null, false, false);
    }

    private AgentEvent.Payload failurePayload(String name, String errorCode) {
        return new AgentEvent.Payload("FAILED", name, null, null, null, null, null, "FAILED",
                List.of(), null, null, null, AgentRun.ErrorCode.valueOf(errorCode), false, false);
    }

    private String bounded(String value, int limit) {
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private void fail(Long runId, Instant started, String code, int modelCalls, int toolCalls) {
        Instant finished = clock.instant();
        runs.failKnowledge(runId, code, modelCalls + toolCalls, modelCalls, toolCalls,
                Math.max(0, Duration.between(started, finished).toMillis()), finished);
    }

    static AssistantMessage completedAssistantMessage(NodeOutput output) {
        if (!(output instanceof StreamingOutput<?> streaming)
                || streaming.getOutputType() != OutputType.AGENT_MODEL_FINISHED) {
            return null;
        }
        if (streaming.getOriginData() instanceof ChatResponse response
                && response.getResult() != null) {
            return response.getResult().getOutput();
        }
        return streaming.message() instanceof AssistantMessage message ? message : null;
    }

    static String publicProgressText(AssistantMessage message) {
        if (message == null || !message.hasToolCalls() || message.getText() == null
                || message.getText().isBlank()) {
            return null;
        }
        String text = message.getText().strip();
        int count = text.codePointCount(0, text.length());
        return count <= MAX_PUBLIC_PROGRESS_CODE_POINTS ? text
                : text.substring(0, text.offsetByCodePoints(0, MAX_PUBLIC_PROGRESS_CODE_POINTS));
    }

    static boolean isPublicFinalResponse(AssistantMessage message) {
        return message != null && !message.hasToolCalls()
                && message.getText() != null && !message.getText().isBlank();
    }

    static ToolExecutionExceptionProcessor toolExceptionProcessor() {
        return exception -> {
            Throwable cause = exception.getCause();
            if (cause instanceof KnowledgeDraftException draftFailure
                    && draftFailure.code() == KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION) {
                throw draftFailure;
            }
            if (!(cause instanceof RuntimeException)) {
                throw exception;
            }
            String message = cause.getMessage();
            return "TOOL_ERROR: " + (message == null || message.isBlank() ? "TOOL_EXECUTION_FAILED" : message);
        };
    }

    static String errorCode(Exception exception) {
        Throwable failure = reactor.core.Exceptions.unwrap(exception);
        while (failure != null) {
            if (failure instanceof KnowledgeDraftException draftFailure
                    && draftFailure.code() == KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION) {
                return "AGENT_TOOL_SCOPE_VIOLATION";
            }
            if (failure instanceof TimeoutException) {
                return "AGENT_RUN_TIMEOUT";
            }
            if (failure instanceof ModelCallLimitExceededException) {
                return "AGENT_MODEL_CALL_LIMIT_EXCEEDED";
            }
            if (failure instanceof ToolCallLimitExceededException) {
                return "AGENT_STEP_LIMIT_EXCEEDED";
            }
            failure = failure.getCause();
        }
        return "AGENT_MODEL_RESPONSE_INVALID";
    }

    /**
     * @return 当前正在执行该工具调用的 Agent 节点名（coordinator/retriever/drafter/reviewer）。
     *         工具发生在某个 ReactAgent 子图内，框架在 RunnableConfig.metadata 的 {@code _AGENT_} 中写入
     *         {@code subgraph_<节点名>}，据此去掉前缀即可稳定归属，避免前端靠阶段事件时间推断。
     */
    private static String agentNode(ToolCallRequest request) {
        return agentNode(request.getContext());
    }

    /** @return 当前正在执行该模型调用的 Agent 节点名（与工具归属同一 {@code _AGENT_} 来源）。 */
    private static String agentNode(ModelRequest request) {
        return agentNode(request.getContext());
    }

    private static String agentNode(Map<String, Object> context) {
        Object value = context == null ? null : context.get("_AGENT_");
        String agent = value == null ? null : String.valueOf(value);
        if (agent != null && agent.startsWith("subgraph_")) {
            agent = agent.substring("subgraph_".length());
        }
        return agent;
    }
}
