package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.InterruptionHook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import io.github.loredock.knowledge.api.KnowledgeDraftException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 知识整理运行的薄执行接线：直接构建框架 ReactAgent、Skill Hook 与 PostgresSaver，
 * 只负责既有 run 的状态和公开消息投影，不实现模型/Tool 循环、子 Agent 调度或 Checkpoint。
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
    private final AgentRunMapper runs;
    private final KnowledgeTaskConversationMapper conversations;
    private final KnowledgeTaskMessageMapper messages;
    private final AgentEventService events;
    private final KnowledgeTaskRunProjectionService projection;
    private final BoundedAgentRunScheduler scheduler;
    private final Clock clock;
    private final Map<Long, RunningAgent> active = new ConcurrentHashMap<>();

    /** 注入框架组件、既有运行持久化和共享有界调度器。 */
    public KnowledgeCurationRunExecutor(
            ObjectProvider<ChatModel> models,
            AgentProperties properties,
            ToolCallbackResolver toolResolver,
            PostgresSaver checkpoints,
            AgentRunMapper runs,
            KnowledgeTaskConversationMapper conversations,
            KnowledgeTaskMessageMapper messages,
            AgentEventService events,
            KnowledgeTaskRunProjectionService projection,
            BoundedAgentRunScheduler scheduler,
            Clock clock
    ) {
        this.models = models;
        this.properties = properties;
        this.toolResolver = toolResolver;
        this.checkpoints = checkpoints;
        this.runs = runs;
        this.conversations = conversations;
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
            fail(run.getId(), run.getAcceptedAt(), "AGENT_MODEL_UNAVAILABLE", 0, 0);
            return;
        }
        if (!scheduler.schedule(run.getId(), () -> execute(run, goal, null, definition))) {
            fail(run.getId(), run.getAcceptedAt(), "AGENT_EXECUTOR_SATURATED", 0, 0);
        }
    }

    /** 框架在下一个 InterruptionHook 安全边界消费空反馈并提交 Checkpoint。 */
    public void requestPause(AgentRunEntity run) {
        RunningAgent running = active.get(run.getId());
        if (running != null) {
            running.agent().interrupt(config(run));
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
        RunningAgent running = active.computeIfAbsent(run.getId(), ignored -> build(run, goal, definition));
        ReactAgent agent = running.agent();
        RunMetrics metrics = running.metrics();
        RunnableConfig config = config(run);
        try {
            events.append(run.getId(), resuming ? AgentEventType.AGENT_STAGE : AgentEventType.RUN_STARTED,
                    AgentEvent.SubjectType.AGENT,
                    payload(resuming ? "RESUMING" : "PREPARING", run.getAgentName(), "RUNNING"), started);
            Flux<org.springframework.ai.chat.messages.Message> stream;
            if (resuming) {
                agent.interrupt(guidance, config);
                stream = agent.streamMessages(Map.of(), config);
            } else {
                stream = agent.streamMessages(goal, config);
            }
            AssistantMessage result = stream.ofType(AssistantMessage.class)
                    .doOnNext(message -> persistPublicProgress(run, message))
                    .filter(message -> !message.hasToolCalls())
                    .timeout(properties.totalTimeout())
                    .last(new AssistantMessage(""))
                    .block();
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
            runs.completeKnowledge(run.getId(), text, metrics.steps(), metrics.modelCalls(), metrics.toolCalls(),
                    Duration.between(started, finished).toMillis(), finished);
            active.remove(run.getId(), running);
            System.out.println("知识整理模型最终原始响应：" + text);
        } catch (Exception exception) {
            AgentRunEntity current = runs.selectById(run.getId());
            if (current != null && "PAUSE_REQUESTED".equals(current.getStatus())
                    && projection.markWaitingAfterInterrupt(run.getId())) {
                return;
            }
            active.remove(run.getId(), running);
            String code = errorCode(exception);
            events.append(run.getId(), AgentEventType.RUN_FAILED, AgentEvent.SubjectType.AGENT,
                    failurePayload(run.getAgentName(), code), clock.instant());
            fail(run.getId(), started, code, metrics.modelCalls(), metrics.toolCalls());
            log.warn("knowledge_task failed conversationId={} runId={} errorCode={} modelCalls={} toolCalls={}",
                    run.getKnowledgeTaskConversationId(), run.getId(), code,
                    metrics.modelCalls(), metrics.toolCalls(), exception);
        }
    }

    private RunningAgent build(
            AgentRunEntity run,
            String goal,
            KnowledgeAgentDefinitionService.LoadedDefinition definition
    ) {
        RunMetrics metrics = new RunMetrics(run.getId());
        ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
                .runLimit(properties.limits().curationMaxModelCalls())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
        ToolCallLimitHook toolLimit = ToolCallLimitHook.builder()
                .runLimit(properties.limits().curationMaxToolCalls())
                .exitBehavior(ToolCallLimitHook.ExitBehavior.ERROR)
                .build();
        SkillsAgentHook skillHook = definition.createSkillHook(toolResolver);
        ReactAgent agent = ReactAgent.builder()
                .name("knowledge-curation-" + run.getId())
                .instruction("目标：" + goal + "。先用 read_skill 激活 " + definition.runtime().skillName()
                        + " Skill；修改草稿必须先读后改，正式发布由管理员完成。"
                        + "Tool 调用前如需说明，只输出简短的公开行动摘要，不输出内部推理。")
                .model(Objects.requireNonNull(models.getIfAvailable(), "知识整理模型不可用"))
                .tools(List.of())
                .toolContext(Map.of(
                        "operatorId", run.getOperatorId(),
                        "projectIdentifier", run.getProjectIdentifier(),
                        "conversationId", run.getKnowledgeTaskConversationId(),
                        "runId", run.getId()))
                .hooks(skillHook, InterruptionHook.builder().build(), modelLimit, toolLimit)
                .interceptors(metrics, metrics.toolInterceptor())
                .toolExecutionExceptionProcessor(toolExceptionProcessor())
                .saver(checkpoints)
                .releaseThread(false)
                .parallelToolExecution(false)
                .build();
        return new RunningAgent(agent, metrics);
    }

    private void persistPublicProgress(AgentRunEntity run, AssistantMessage message) {
        String progress = publicProgressText(message);
        if (progress == null) {
            return;
        }
        Instant now = clock.instant();
        messages.insert(KnowledgeTaskMessageEntity.builder()
                .conversationId(run.getKnowledgeTaskConversationId()).runId(run.getId())
                .role("COORDINATOR_AGENT").subjectName("公开行动摘要")
                .content(progress).createdAt(now).build());
        conversations.update(null, Wrappers.<KnowledgeTaskConversationEntity>lambdaUpdate()
                .set(KnowledgeTaskConversationEntity::getUpdatedAt, now)
                .eq(KnowledgeTaskConversationEntity::getId, run.getKnowledgeTaskConversationId()));
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

    /**
     * 把可修正的业务 Tool 参数错误返回给模型继续推理，但范围越界仍立即终止运行。
     * Spring AI 默认支持此类自纠；这里只补充 LoreDock 的范围安全边界。
     */
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

    /** 框架 Interceptor 记录真实经过模型与 Tool 节点的次数，不推测调用数量。 */
    private final class RunMetrics extends ModelInterceptor {
        private static final int MAX_TOOL_PREVIEW_CODE_POINTS = 500;
        private final Long runId;
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();

        private RunMetrics(Long runId) {
            this.runId = runId;
        }

        @Override
        public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
            modelCalls.incrementAndGet();
            return handler.call(request);
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
                    events.append(runId, AgentEventType.TOOL_STARTED, AgentEvent.SubjectType.TOOL,
                            toolPayload(toolName, purpose, null, null, "STARTED", false), started);
                    try {
                        ToolCallResponse response = handler.call(request);
                        long durationMillis = Duration.between(started, clock.instant()).toMillis();
                        boolean failed = response.isError() || (response.getResult() != null
                                && response.getResult().startsWith("TOOL_ERROR: "));
                        String preview = preview(response.getResult());
                        events.append(runId, AgentEventType.TOOL_COMPLETED, AgentEvent.SubjectType.TOOL,
                                toolPayload(toolName, purpose, safeResultSummary(toolName, failed),
                                        durationMillis, failed ? "FAILED" : "COMPLETED", false),
                                clock.instant());
                        System.out.println("知识整理工具调用：tool=" + toolName + "，结果预览=" + preview);
                        return response;
                    } catch (RuntimeException exception) {
                        long durationMillis = Duration.between(started, clock.instant()).toMillis();
                        events.append(runId, AgentEventType.TOOL_COMPLETED, AgentEvent.SubjectType.TOOL,
                                toolPayload(toolName, purpose, "工具执行失败", durationMillis, "FAILED", false),
                                clock.instant());
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
                case "read_skill" -> "已加载知识整理工作流";
                case "selected_draft_list" -> "已读取本次固定输入清单";
                case "selected_draft_read" -> "已读取一份固定输入草稿";
                case "knowledge_directory_list" -> "已读取知识库目录";
                case "knowledge_document_list" -> "已读取目录下文档清单";
                case "knowledge_document_read" -> "已读取相关文档全文";
                case "knowledge_grep" -> "已完成关键词匹配";
                case "knowledge_search" -> "已完成近似文档检索";
                case "finding_record" -> "已记录一项整理发现";
                case "draft_create" -> "已创建合并草稿";
                case "draft_read" -> "已读取当前合并草稿";
                case "draft_update" -> "已生成合并草稿新修订";
                case "draft_diff" -> "已生成待审核 Diff";
                default -> "工具执行完成";
            };
        }

        private String toolPurpose(String toolName) {
            return switch (toolName) {
                case "read_skill" -> "加载整理规则";
                case "selected_draft_list", "selected_draft_read" -> "读取选中的待处理草稿";
                case "knowledge_directory_list", "knowledge_document_list" -> "浏览现有知识库";
                case "knowledge_document_read" -> "核对现有文档全文";
                case "knowledge_grep", "knowledge_search" -> "检索相关业务知识";
                case "finding_record" -> "记录重复、冲突、过期或缺口";
                case "draft_create", "draft_read", "draft_update" -> "生成或修改合并草稿";
                case "draft_diff" -> "生成审核差异";
                default -> "执行知识整理步骤";
            };
        }
    }

    private record RunningAgent(ReactAgent agent, RunMetrics metrics) {
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

    private String bounded(String value) {
        String text = value == null || value.isBlank() ? "知识整理运行已完成" : value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= MAX_FINAL_CODE_POINTS ? text
                : text.substring(0, text.offsetByCodePoints(0, MAX_FINAL_CODE_POINTS));
    }

    private void fail(Long runId, Instant started, String code, int modelCalls, int toolCalls) {
        Instant finished = clock.instant();
        runs.failKnowledge(runId, code, modelCalls + toolCalls, modelCalls, toolCalls,
                Math.max(0, Duration.between(started, finished).toMillis()), finished);
    }
}
