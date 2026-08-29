package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.model.result.KnowledgeCurationGraphResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * 知识整理多 Agent Graph 的组装工厂。
 *
 * <p>本类只组装框架组件：按照设计白名单为每个专家 {@code ReactAgent} 筛选 Tool、设置结构化
 * {@code outputType}、用 {@code asNode(false,false)} 接入父 {@code StateGraph}（不继承前序 Agent
 * 完整消息历史），编排条件边与状态合并策略，注册 {@code PostgresSaver} 作为 Checkpoint，并在关键节点
 * 后配置 {@code interruptAfter} 边界。它不实现模型/Tool 循环，也不实现通用工作流引擎。</p>
 *
 * <p>两个公开能力：{@link #validate(List, List)} 做纯规格与 Tool 白名单校验，供
 * {@code KnowledgeAgentDefinitionService} 启动时快速失败；{@link #build(AgentSpecSet, ChatModel,
 * ToolCallbackResolver, Map, PostgresSaver)} 组装可运行的 {@code CompiledGraph}。</p>
 *
 * <p>状态键与合并策略严格对应设计文档“Graph State”一节：messages 使用 {@code APPEND}，其余路由字段
 * 使用 {@code REPLACE}。条件边不信任自然语言，路由节点通过 Jackson 解析前序专家节点写入 state 的
 * 结构化输出得到稳定路由依据；结构化输出无法解析或字段非法时按安全失败处理（见各 Route 方法）。</p>
 */
public class KnowledgeCurationGraphFactory {

    /** 调度 Agent 名称；无业务 Tool，只输出结构化决策。 */
    public static final String COORDINATOR = "coordinator";
    /** 检索 Agent 名称；只读取候选与现有知识，提交证据事实。 */
    public static final String RETRIEVER = "retriever";
    /** 草稿 Agent 名称；只根据检索结论与调度要求写入工作草稿。 */
    public static final String DRAFTER = "drafter";
    /** 审查 Agent 名称；独立核对来源、最新草稿与 Diff。 */
    public static final String REVIEWER = "reviewer";

    /** 启动时要求存在的四个角色，顺序与设计一致。 */
    public static final List<String> ROLES = List.of(COORDINATOR, RETRIEVER, DRAFTER, REVIEWER);

    /** 调度 Agent：无业务 Tool。 */
    public static final List<String> COORDINATOR_TOOLS = List.of();
    /** 检索 Agent：只读候选、正式知识与当前工作区，无写 Tool。 */
    public static final List<String> RETRIEVER_TOOLS = List.of(
            "selected_draft_list", "selected_draft_read",
            "knowledge_directory_list", "knowledge_document_list",
            "knowledge_search", "knowledge_grep", "knowledge_document_read",
            "workspace_document_list", "draft_read");
    /** 草稿 Agent：允许读取与写入工作草稿，不扩大检索、不改正式知识、不发布。 */
    public static final List<String> DRAFTER_TOOLS = List.of(
            "selected_draft_read", "knowledge_document_read", "workspace_document_list",
            "draft_create", "draft_read", "draft_update", "draft_rename", "draft_diff");
    /** 审查 Agent：检索 Agent 的全部只读 Tool + 工作区与 Diff，无写 Tool、无发布。 */
    public static final List<String> REVIEWER_TOOLS = List.of(
            "selected_draft_list", "selected_draft_read",
            "knowledge_directory_list", "knowledge_document_list",
            "knowledge_search", "knowledge_grep", "knowledge_document_read",
            "workspace_document_list", "draft_read", "draft_diff");

    /** 各角色对应的设计 Tool 白名单。 */
    public static final Map<String, List<String>> DESIGN_TOOLS = Map.of(
            COORDINATOR, COORDINATOR_TOOLS,
            RETRIEVER, RETRIEVER_TOOLS,
            DRAFTER, DRAFTER_TOOLS,
            REVIEWER, REVIEWER_TOOLS);

    /** 各角色对应的结构化输出记录类型。 */
    public static final Map<String, Class<?>> OUTPUT_TYPES = Map.of(
            COORDINATOR, KnowledgeCurationGraphResult.CoordinatorResult.class,
            RETRIEVER, KnowledgeCurationGraphResult.RetrievalResult.class,
            DRAFTER, KnowledgeCurationGraphResult.DraftResult.class,
            REVIEWER, KnowledgeCurationGraphResult.ReviewResult.class);

    /** 各角色结构化结果写入 Graph State 的键。 */
    public static final Map<String, String> OUTPUT_KEYS = Map.of(
            COORDINATOR, "coordinationResult",
            RETRIEVER, "retrievalResult",
            DRAFTER, "draftResult",
            REVIEWER, "reviewResult");

    /**
     * 各状态推进节点注入 messages 时使用的带标签上下文前缀。框架会把每个 Agent 的最后一条结构化输出
     * （原始 JSON）自动追加到父 messages，导致下个 Agent 看到一串无标签、且含调度 Agent 自身早期输出的
     * 原始 JSON，难以识别所处阶段。这里额外注入带明确标签与阶段含义的上下文，让 Agent 优先读取。
     */
    private static final String RETRIEVAL_CONTEXT = "【检索结果 · 供调度决策】";
    private static final String DECISION_CONTEXT = "【调度决策 · 草稿写入要求】";
    private static final String DRAFT_CONTEXT = "【草稿结果 · 本次修订】";
    private static final String REVIEW_CONTEXT = "【审查结果】";
    private static final String STAGE_DECIDE_INSTRUCTION =
            "【当前阶段：DECIDE】\n检索已完成。请依据上方检索结果决定下一步：只能输出 DRAFT、ASK_USER 或 NO_CHANGE。切勿输出 CHAT 或 RETRIEVE。";
    private static final String STAGE_FINISH_INSTRUCTION =
            "【当前阶段：FINISH】\n所有工作已完成。你作为调度 Agent 是唯一汇报口径：请输出 action=END，并在 summary 给出面向管理员的最终汇报（结论、主要依据、已写入/未写入内容、待人工判断项）。切勿再次输出“请提供…”“现在开始检索…”等开场白。";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCurationGraphFactory.class);

    private final ObjectMapper objectMapper;

    /** @param objectMapper 解析各专家结构化结果的 Jackson 实例 */
    public KnowledgeCurationGraphFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
    }

    /**
     * 校验四份 Agent 定义的名称、角色与 Tool 白名单。
     *
     * <p>违反时抛出 {@link IllegalArgumentException}，使应用启动失败，而不是沿用框架“空列表代表全部
     * Tool”或“未知 Tool 静默忽略”的默认行为。</p>
     *
     * @param specs 框架解析出的四份 Agent 定义
     * @param availableToolNames 服务端实际注册的全部业务 Tool 名称
     * @throws IllegalArgumentException 缺少角色、名称不唯一、声明未知 Tool 或白名单与设计不一致
     */
    public void validate(List<AgentSpec> specs, List<String> availableToolNames) {
        Objects.requireNonNull(specs, "agent specs");
        if (specs.stream().map(AgentSpec::name).distinct().count() != specs.size()) {
            throw new IllegalArgumentException("Agent 名称必须唯一");
        }
        for (String role : ROLES) {
            if (specs.stream().noneMatch(spec -> role.equals(spec.name()))) {
                throw new IllegalArgumentException("缺少知识整理 Agent 角色：" + role);
            }
        }
        for (AgentSpec spec : specs) {
            List<String> declared = spec.toolNames();
            List<String> design = DESIGN_TOOLS.get(spec.name());
            if (design == null) {
                throw new IllegalArgumentException("未知知识整理 Agent：" + spec.name());
            }
            for (String tool : declared) {
                if (!availableToolNames.contains(tool)) {
                    throw new IllegalArgumentException(
                            "Agent " + spec.name() + " 声明了未知 Tool：" + tool);
                }
            }
            if (!declared.equals(design)) {
                throw new IllegalArgumentException("Agent " + spec.name() + " 的 Tool 白名单与设计不一致："
                        + declared + "，应为 " + design);
            }
        }
    }

    /**
     * 组装编译后的知识整理 Graph。
     *
     * @param agents 由定义服务加载并校验过的四份 Agent 规格
     * @param model 本轮共享 ChatModel
     * @param resolver 按名称解析每个 Agent 的 Tool Callback
     * @param toolContext 服务端固定范围（操作者、项目、会话、run）
     * @param saver 项目统一的 PostgreSQL Checkpoint Saver
     * @param hooks 本轮共享的 Agent Hook（调用限额、中断、幂等等）
     * @param interceptors 本轮共享的模型/Tool Interceptor（统一计数与事件采集）
     * @param exceptionProcessor 写类 Tool 参数错误的统一自纠处理器
     * @return 已编译的 Graph 与四份 Agent 摘要
     */
    public GraphBundle build(
            AgentSpecSet agents,
            ChatModel model,
            ToolCallbackResolver resolver,
            Map<String, Object> toolContext,
            PostgresSaver saver,
            List<? extends Hook> hooks,
            List<? extends Interceptor> interceptors,
            ToolExecutionExceptionProcessor exceptionProcessor
    ) throws GraphStateException {
        Objects.requireNonNull(model, "chat model");
        Objects.requireNonNull(saver, "checkpoint saver");
        StateGraph graph = new StateGraph(keyStrategies());
        Map<String, ReactAgent> built = new LinkedHashMap<>();
        for (String role : ROLES) {
            AgentSpec spec = agents.spec(role);
            ReactAgent agent = ReactAgent.builder()
                    .name(role)
                    .instruction(agents.instruction(role))
                    .templateRenderer((template, params) -> template)
                    .model(model)
                    .tools(resolveTools(spec.toolNames(), resolver))
                    .toolContext(toolContext)
                    .outputType(OUTPUT_TYPES.get(role))
                    .outputKey(OUTPUT_KEYS.get(role))
                    .saver(saver)
                    .hooks(hooks)
                    .interceptors(interceptors)
                    .toolExecutionExceptionProcessor(exceptionProcessor)
                    .releaseThread(false)
                    .parallelToolExecution(false)
                    .build();
            built.put(role, agent);
            // 关键：includeContents=true 会把父 Graph state 的 messages 传入本 Agent 的子图，作为它的用户输入。
            // 若为 false，框架会移除 messages，导致本 Agent 只拿到系统指令、没有任何用户消息，调度 Agent 因此无法把
            // “整理勾选草稿”识别为 RETRIEVE，而会当作闲聊 CHAT 短路（实测 bug）。父 messages 只保存本轮 goal 一条，
            // 各 Agent 的输出都写回各自的 outputKey（coordinationResult 等），不会把某个 Agent 的内部推理传回父 messages，
            // 因此这里不会造成“继承前序 Agent 完整消息历史”的设计顾虑。
            graph.addNode(role, agent.asNode(true, false));
        }
        // 状态推进与上下文合成节点：在推进 stage 的同时，把上一环的结构化结果以带标签的消息追加到 messages，
        // 使下一环 Agent 优先读取到清晰、可识别阶段的前序结果（修掉“只有原始 JSON、调度 Agent 误判阶段”的问题）。
        graph.addNode("set_decide", (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            out.put("stage", "DECIDE");
            appendContextAndStage(out, state, "retrievalResult", RETRIEVAL_CONTEXT, STAGE_DECIDE_INSTRUCTION);
            log.info("knowledge graph 节点推进 set_decide：stage -> DECIDE");
            return CompletableFuture.completedFuture(out);
        });
        // 调度 Agent 的两条结束路径先推进到 FINISH 再回到调度 Agent 汇总；此处注入审查结果供收尾总结 + 明确 FINISH 阶段。
        graph.addNode("set_finish", (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            out.put("stage", "FINISH");
            appendContextAndStage(out, state, "reviewResult", REVIEW_CONTEXT, STAGE_FINISH_INSTRUCTION);
            log.info("knowledge graph 节点推进 set_finish：stage -> FINISH");
            return CompletableFuture.completedFuture(out);
        });
        // 审查返工节点：REVISE 未达上限时递增起草轮数再回到草稿 Agent，保证最多返工两轮（draftRound 最大 2）。
        graph.addNode("set_draft_round", (AsyncNodeAction) state -> {
            int round = integer(state, "draftRound") + 1;
            Map<String, Object> out = new HashMap<>();
            out.put("draftRound", round);
            putContext(out, state, "reviewResult", REVIEW_CONTEXT);
            log.info("knowledge graph 节点推进 set_draft_round：REVISE 未达上限，draftRound -> {}（最大 2），注入候选=审查结果", round);
            return CompletableFuture.completedFuture(out);
        });
        // 草稿与审查节点各自的入口前合成上下文：草稿看到“检索结果 + 调度决策”，审查看到“检索结果 + 调度决策 + 草稿结果”。
        graph.addNode("set_draft_context", (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            putContext(out, state, "coordinationResult", DECISION_CONTEXT);
            log.info("knowledge graph 节点推进 set_draft_context：注入候选=调度决策 {}", !out.isEmpty());
            return CompletableFuture.completedFuture(out);
        });
        graph.addNode("set_review_context", (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            putContext(out, state, "draftResult", DRAFT_CONTEXT);
            log.info("knowledge graph 节点推进 set_review_context：注入候选=草稿结果 {}", !out.isEmpty());
            return CompletableFuture.completedFuture(out);
        });

        // 父 Graph 从 START 进入调度 Agent，保证存在唯一的有效入口点。
        graph.addEdge(StateGraph.START, COORDINATOR);

        graph.addConditionalEdges(COORDINATOR, coordinatorRouter(), coordinatorRoutes());
        graph.addEdge(RETRIEVER, "set_decide");
        graph.addEdge("set_decide", COORDINATOR);

        graph.addConditionalEdges(DRAFTER, draftRouter(), draftRoutes());
        graph.addConditionalEdges(REVIEWER, reviewRouter(), reviewRoutes());
        graph.addEdge("set_draft_round", DRAFTER);
        graph.addEdge("set_finish", COORDINATOR);
        // 调度 DECIDE(草稿动作)→合成调度决策→草稿；草稿 WRITTEN→合成草稿结果→审查。
        graph.addEdge("set_draft_context", DRAFTER);
        graph.addEdge("set_review_context", REVIEWER);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptAfter(COORDINATOR, RETRIEVER, DRAFTER, REVIEWER, "set_finish")
                .build();
        return new GraphBundle(graph.compile(compileConfig), built, agents.definitions());
    }

    private KeyStrategyFactory keyStrategies() {
        return KeyStrategy.builder()
                .addStrategy("messages", KeyStrategy.APPEND)
                .addStrategy("stage", KeyStrategy.REPLACE)
                .addStrategy("goal", KeyStrategy.REPLACE)
                .addStrategy("coordinationResult", KeyStrategy.REPLACE)
                .addStrategy("retrievalResult", KeyStrategy.REPLACE)
                .addStrategy("draftResult", KeyStrategy.REPLACE)
                .addStrategy("reviewResult", KeyStrategy.REPLACE)
                .addStrategy("draftRound", KeyStrategy.REPLACE)
                .addStrategy("finishReason", KeyStrategy.REPLACE)
                .build();
    }

    private List<ToolCallback> resolveTools(List<String> names, ToolCallbackResolver resolver) {
        return names.stream().map(resolver::resolve).toList();
    }

    private AsyncEdgeAction coordinatorRouter() {
        return state -> CompletableFuture.completedFuture(coordinatorRoute(state));
    }

    private AsyncEdgeAction draftRouter() {
        return state -> CompletableFuture.completedFuture(draftRoute(state));
    }

    private AsyncEdgeAction reviewRouter() {
        return state -> CompletableFuture.completedFuture(reviewRoute(state));
    }

    /**
     * 调度 Agent 条件边：stage=START 时按 CHAT/RETRIEVE 决定直接结束或进入检索；
     * stage=DECIDE 时按 DRAFT/ASK_USER/NO_CHANGE 决定进入草稿或结束；stage=FINISH 时结束。
     */
    String coordinatorRoute(com.alibaba.cloud.ai.graph.OverAllState state) {
        String stage = text(state, "stage");
        KnowledgeCurationGraphResult.CoordinatorResult result =
                structured(state, "coordinationResult", KnowledgeCurationGraphResult.CoordinatorResult.class);
        if (stage == null || result == null || result.action() == null) {
            throw new IllegalStateException("调度 Agent 结构化结果无效");
        }
        String route;
        if ("FINISH".equals(stage)) {
            route = "FINISH";
        } else if ("DECIDE".equals(stage)) {
            route = switch (result.action()) {
                case DRAFT -> {
                    // §9：DECIDE 输出 DRAFT 但没有任何 SUPPORTED 事实或没有 draftInstruction，拒绝进入草稿节点。
                    KnowledgeCurationGraphResult.RetrievalResult retrieval =
                            structured(state, "retrievalResult", KnowledgeCurationGraphResult.RetrievalResult.class);
                    boolean hasSupportedFact = retrieval != null && retrieval.facts().stream()
                            .anyMatch(fact -> fact.support() == KnowledgeCurationGraphResult.FactSupport.SUPPORTED);
                    if (isBlank(result.draftInstruction()) || !hasSupportedFact) {
                        throw new IllegalStateException("调度 Agent 在 DECIDE 阶段输出 DRAFT 但缺少写入要求或已支持事实");
                    }
                    yield "DRAFT";
                }
                case ASK_USER -> {
                    // §9：ASK_USER 必须提出具体问题，否则结束本轮不合法。
                    if (isBlank(result.question())) {
                        throw new IllegalStateException("调度 Agent 在 DECIDE 阶段输出 ASK_USER 但没有具体问题");
                    }
                    yield "ASK_USER";
                }
                case NO_CHANGE -> "NO_CHANGE";
                default -> throw new IllegalStateException("DECIDE 阶段调度动作无效：" + result.action());
            };
        } else {
            // START
            route = switch (result.action()) {
                case CHAT -> {
                    if (isBlank(result.summary())) {
                        throw new IllegalStateException("调度 Agent 在 START 阶段输出 CHAT 但没有可见回复");
                    }
                    yield "CHAT";
                }
                case RETRIEVE -> "RETRIEVE";
                default -> throw new IllegalStateException("START 阶段调度动作无效：" + result.action());
            };
        }
        log.info("knowledge graph 条件边 coordinatorRoute：stage={} action={} -> {}（reason={}）",
                stage, result.action(), route, result.reason());
        return route;
    }

    /** 草稿 Agent 条件边：BLOCKED 结束；WRITTEN 进入审查；WRITTEN 缺少修订标识时安全失败（§9）。 */
    String draftRoute(com.alibaba.cloud.ai.graph.OverAllState state) {
        KnowledgeCurationGraphResult.DraftResult result =
                structured(state, "draftResult", KnowledgeCurationGraphResult.DraftResult.class);
        if (result == null || result.status() == null) {
            throw new IllegalStateException("草稿 Agent 结构化结果无效");
        }
        String route = switch (result.status()) {
            case WRITTEN -> {
                if (result.drafts().isEmpty()) {
                    throw new IllegalStateException("草稿 Agent 输出 WRITTEN 但没有 draftId + revision");
                }
                yield "WRITTEN";
            }
            case BLOCKED -> "BLOCKED";
        };
        log.info("knowledge graph 条件边 draftRoute：status={} -> {}（summary={}）", result.status(), route, result.summary());
        return route;
    }

    /** 审查 Agent 条件边：PASS/ASK_USER 结束；REVISE 未达上限返工，达到上限后结束并提示人工（§9）。 */
    String reviewRoute(com.alibaba.cloud.ai.graph.OverAllState state) {
        KnowledgeCurationGraphResult.ReviewResult result =
                structured(state, "reviewResult", KnowledgeCurationGraphResult.ReviewResult.class);
        if (result == null || result.verdict() == null) {
            throw new IllegalStateException("审查 Agent 结构化结果无效");
        }
        int draftRound = integer(state, "draftRound");
        String route = switch (result.verdict()) {
            case PASS -> {
                // §9：PASS 必须绑定草稿 Agent 本轮返回的全部最新修订。
                if (result.reviewedDrafts().isEmpty()) {
                    throw new IllegalStateException("审查 Agent 输出 PASS 但没有 reviewedDrafts");
                }
                yield "PASS";
            }
            case ASK_USER -> {
                if (isBlank(result.question())) {
                    throw new IllegalStateException("审查 Agent 输出 ASK_USER 但没有具体问题");
                }
                yield "ASK_USER";
            }
            case REVISE -> {
                // §9：REVISE 必须至少有一条可执行的 finding。
                if (result.findings().isEmpty()) {
                    throw new IllegalStateException("审查 Agent 输出 REVISE 但没有可执行 finding");
                }
                yield draftRound < 2 ? "REVISE" : "REVISE_LIMIT";
            }
        };
        log.info("knowledge graph 条件边 reviewRoute：verdict={} draftRound={} -> {}（summary={}）",
                result.verdict(), draftRound, route, result.summary());
        return route;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, String> coordinatorRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("CHAT", StateGraph.END);
        routes.put("RETRIEVE", RETRIEVER);
        routes.put("DRAFT", "set_draft_context");
        routes.put("ASK_USER", "set_finish");
        routes.put("NO_CHANGE", "set_finish");
        routes.put("FINISH", StateGraph.END);
        return routes;
    }

    private Map<String, String> draftRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("BLOCKED", "set_finish");
        routes.put("WRITTEN", "set_review_context");
        return routes;
    }

    private Map<String, String> reviewRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("PASS", "set_finish");
        routes.put("ASK_USER", "set_finish");
        routes.put("REVISE", "set_draft_round");
        routes.put("REVISE_LIMIT", "set_finish");
        return routes;
    }

    private <T> T structured(com.alibaba.cloud.ai.graph.OverAllState state, String key, Class<T> type) {
        Object value = state.data().get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        String text;
        if (value instanceof AssistantMessage message) {
            text = message.getText();
        } else if (value instanceof String string) {
            text = string;
        } else if (value instanceof Map<?, ?> map) {
            // Checkpoint 恢复后节点输出被框架序列化为 Map，需重新取出正文文本再解析。
            text = messageTextFromMap(map);
        } else {
            text = value.toString();
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 结构化结果无法解析：" + key, exception);
        }
    }

    /** @return 从框架序列化的消息 Map 中取出正文文本；无法定位时返回 null 触发安全失败。 */
    private static String messageTextFromMap(Map<?, ?> map) {
        for (String textKey : List.of("text", "textContent")) {
            Object value = map.get(textKey);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
            if (value instanceof Map<?, ?> nested) {
                Object inner = nested.get("text");
                if (inner instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
            Object content = map.get("content");
            if (content instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return String.valueOf(map);
    }

    /** 若指定结果键已产出，则以带标签的用户消息追加到返回的 state 更新里（messages 为 APPEND 会追加）。 */
    private static void putContext(Map<String, Object> out, com.alibaba.cloud.ai.graph.OverAllState state,
            String resultKey, String label) {
        Message context = contextMessage(state, resultKey, label);
        if (context != null) {
            out.put("messages", List.of(context));
        }
    }

    /** 追加前序结果上下文（若有）与一条明确的【当前阶段】指令，供调度 Agent 可靠识别所处阶段（修 NO_CHANGE 等无草稿/审查路径误判）。 */
    private static void appendContextAndStage(Map<String, Object> out, com.alibaba.cloud.ai.graph.OverAllState state,
            String resultKey, String label, String stageInstruction) {
        List<Message> messages = new ArrayList<>();
        Message context = contextMessage(state, resultKey, label);
        if (context != null) {
            messages.add(context);
        }
        messages.add(new UserMessage(stageInstruction));
        out.put("messages", messages);
    }

    /** @return 把前序结构化结果编码成一条带阶段标签的用户消息，用于让下一环 Agent 明确识别所处阶段与可用事实。 */
    private static Message contextMessage(com.alibaba.cloud.ai.graph.OverAllState state, String resultKey, String label) {
        Object value = state.data().get(resultKey);
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof AssistantMessage message) {
            text = message.getText();
        } else if (value instanceof String string) {
            text = string;
        } else if (value instanceof Map<?, ?> map) {
            text = messageTextFromMap(map);
        } else {
            text = String.valueOf(value);
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        return new UserMessage(label + "\n" + text);
    }

    private String text(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state.data().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int integer(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state.data().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** 已校验并可按角色取用的四份 Agent 规格集合。 */
    public record AgentSpecSet(
            List<AgentSpec> definitions
    ) {
        public AgentSpecSet {
            definitions = List.copyOf(definitions);
        }

        /** @param role 角色名 @return 指定角色的规格 */
        public AgentSpec spec(String role) {
            return definitions.stream()
                    .filter(value -> role.equals(value.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("缺少 Agent 角色：" + role));
        }

        public String instruction(String role) {
            String body = spec(role).systemPrompt();
            return body == null || body.isBlank() ? defaultInstruction(role) : body;
        }

        public List<AgentSpec> definitions() {
            return definitions;
        }
    }

    /** 编译后的 Graph 与构建出的四个 Agent。 */
    public record GraphBundle(
            CompiledGraph graph,
            Map<String, ReactAgent> agents,
            List<AgentSpec> definitions
    ) {
    }

    private static String defaultInstruction(String role) {
        return switch (role) {
            case COORDINATOR -> "你是知识整理流程的调度者，只输出结构化决策。";
            case RETRIEVER -> "你是知识整理流程的检索者，只提交证据事实。";
            case DRAFTER -> "你是知识整理流程的写作者，只按已支持事实写入工作草稿。";
            case REVIEWER -> "你是知识整理流程的审查者，独立核对来源与草稿。";
            default -> "你是知识整理流程中的专家 Agent。";
        };
    }

    /** 供执行器使用：同一 threadId 驱动父 Graph 的 Checkpoint 配置。 */
    public static RunnableConfig graphConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }
}
