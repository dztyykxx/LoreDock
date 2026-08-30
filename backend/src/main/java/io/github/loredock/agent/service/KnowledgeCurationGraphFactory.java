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
import com.alibaba.cloud.ai.graph.agent.interceptor.StreamingModelInterceptor;
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

    /** 会话级主 Agent 名称；唯一对话口径，持有三个专家 AgentTool，输出 {@code MainTurnResult}。 */
    public static final String MAIN_AGENT = "main_agent";
    /** 调度 Agent 名称；无业务 Tool，只输出结构化决策。 */
    public static final String COORDINATOR = "coordinator";
    /** 检索 Agent 名称；只读取候选与现有知识，提交证据事实。 */
    public static final String RETRIEVER = "retriever";
    /** 草稿 Agent 名称；只根据检索结论与调度要求写入工作草稿。 */
    public static final String DRAFTER = "drafter";
    /** 审查 Agent 名称；独立核对来源、最新草稿与 Diff。 */
    public static final String REVIEWER = "reviewer";
    /** 会话轮次边界节点：正常轮次完成后标记 WAIT_INPUT，并在该节点后暂停供下一轮续跑。 */
    public static final String TURN_FINISH = "turn_finish";
    /** 重试耗尽恢复门节点：结构化结果持续无效时标记 RECOVERY_REQUIRED 并给出可见说明（不落终态语义见 Executor）。 */
    public static final String RECOVERY_GATE = "recovery_gate";
    /** 结构化结果修复节点前缀：fix_{agent}（agent 在 {@link #FIXABLE}）。 */
    public static final String FIX_PREFIX = "fix_";
    /** 恢复门标记：结构化结果重试耗尽后 turnMode 保留为 RECOVERY_REQUIRED（最终回复按恢复说明结束）。 */
    public static final String RECOVERY_MODE = "RECOVERY_REQUIRED";

    /** Graph 定义版本标识，写入 run 的 config_summary 前缀，用于恢复时与当前定义比对（不匹配停 RECOVERY_REQUIRED）。 */
    public static final String GRAPH_DEF_VERSION = "knowledge-curation-sess-v2";

    /** 启动时要求存在的角色，顺序与设计一致：主 Agent + 完整流程四个专家。 */
    public static final List<String> ROLES = List.of(MAIN_AGENT, COORDINATOR, RETRIEVER, DRAFTER, REVIEWER);

    /** 需要修复回路的 Agent：主 Agent 与完整整理链四个专家。 */
    public static final List<String> FIXABLE = List.of(MAIN_AGENT, COORDINATOR, RETRIEVER, DRAFTER, REVIEWER);

    /** 主 Agent：无业务 Tool，只持有框架 AgentTool（retrieve/draft/review 专家）。 */
    public static final List<String> MAIN_AGENT_TOOLS = List.of();

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
            MAIN_AGENT, MAIN_AGENT_TOOLS,
            COORDINATOR, COORDINATOR_TOOLS,
            RETRIEVER, RETRIEVER_TOOLS,
            DRAFTER, DRAFTER_TOOLS,
            REVIEWER, REVIEWER_TOOLS);

    /** 各角色对应的结构化输出记录类型。 */
    public static final Map<String, Class<?>> OUTPUT_TYPES = Map.of(
            MAIN_AGENT, KnowledgeCurationGraphResult.MainTurnResult.class,
            COORDINATOR, KnowledgeCurationGraphResult.CoordinatorResult.class,
            RETRIEVER, KnowledgeCurationGraphResult.RetrievalResult.class,
            DRAFTER, KnowledgeCurationGraphResult.DraftResult.class,
            REVIEWER, KnowledgeCurationGraphResult.ReviewResult.class);

    /** 各角色结构化结果写入 Graph State 的键。 */
    public static final Map<String, String> OUTPUT_KEYS = Map.of(
            MAIN_AGENT, "mainTurnResult",
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
        // 框架 `.interceptors(...)` 只把拦截器接入模型 `call()` 与工具节点；流式 token 采集必须显式通过
        // `.streamingInterceptors(...)` 接入，否则 `StreamingModelInterceptor.onStreamChunk/afterStreamComplete`
        // 不会触发（project_qa 接线即如此）。这里从共享列表筛出流式拦截器透传给每个 ReactAgent。
        List<StreamingModelInterceptor> streamingInterceptors = interceptors == null ? List.of()
                : interceptors.stream().filter(StreamingModelInterceptor.class::isInstance)
                        .map(StreamingModelInterceptor.class::cast).toList();
        for (String role : ROLES) {
            if (MAIN_AGENT.equals(role)) {
                continue;
            }
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
                    .hooks(hooks)
                    .interceptors(interceptors)
                    .streamingInterceptors(streamingInterceptors)
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
            // 注意子 Agent 不再配置 Saver：AgentTool/子图节点内部运行的确定性线程（{parentThread}_{name}）一旦持有
            // Saver，跨轮直调同一专家会复用其旧 Checkpoint 链，且与父图共享 Postgres 命名空间（会话级编排设计§10.3）。
            graph.addNode(role, agent.asNode(true, false));
        }
        // 主 Agent：无业务 Tool，只持有三个专家 AgentTool；工具名即专家名（AgentTool 采用 agent.name()）。
        ReactAgent main = ReactAgent.builder()
                .name(MAIN_AGENT)
                .instruction(agents.instruction(MAIN_AGENT))
                .templateRenderer((template, params) -> template)
                .model(model)
                .tools(List.of(
                        com.alibaba.cloud.ai.graph.agent.AgentTool.create(built.get(RETRIEVER)),
                        com.alibaba.cloud.ai.graph.agent.AgentTool.create(built.get(DRAFTER)),
                        com.alibaba.cloud.ai.graph.agent.AgentTool.create(built.get(REVIEWER))))
                .toolContext(toolContext)
                .outputType(KnowledgeCurationGraphResult.MainTurnResult.class)
                .outputKey(OUTPUT_KEYS.get(MAIN_AGENT))
                .hooks(hooks)
                .interceptors(interceptors)
                .streamingInterceptors(streamingInterceptors)
                .toolExecutionExceptionProcessor(exceptionProcessor)
                .releaseThread(false)
                .parallelToolExecution(false)
                .build();
        built.put(MAIN_AGENT, main);
        graph.addNode(MAIN_AGENT, main.asNode(true, false));
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

        // 会话轮次边界节点：无论 CHAT 还是 FINISH，都先在此标记本轮完成（WAIT_INPUT），
        // 再在 turn_finish 后的边界暂停。下一轮通过 updateState 注入本轮字段后从该边界指向的
        // Coordinator 继续，上一轮的 goal、消息与结论保留在会话状态中。
        graph.addNode(TURN_FINISH, (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            out.put("turnFinished", true);
            // 正常轮次落在 WAIT_INPUT；重试耗尽恢复门时保留 RECOVERY_REQUIRED，供最终回复按恢复说明结束。
            String priorMode = stateText(state, "turnMode");
            out.put("turnMode", RECOVERY_MODE.equals(priorMode) ? RECOVERY_MODE : "WAIT_INPUT");
            // 阶段 4：重建角色化会话历史并裁剪（旧轮丢弃按整轮、半轮不截断），历史不再由模型原始 JSON 累积。
            List<Message> history = rebuildSessionHistory(state, RECOVERY_MODE.equals(priorMode));
            out.put("conversationHistory", history);
            out.put("historyTruncated", truncatedFlag(state, history));
            log.info("knowledge graph 节点推进 turn_finish：turnMode={}，会话历史={} 条",
                    priorMode, history.size());
            return CompletableFuture.completedFuture(out);
        });

        // 完整整理子图完成后回主 Agent 汇总：注入【当前阶段：FULL CURATION 完成】标记与审查结果。
        graph.addNode("set_main_resume", (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            putContext(out, state, "reviewResult", REVIEW_CONTEXT);
            out.put("messages", appendTo((List<?>) out.remove("messages"), new UserMessage(
                    "【当前阶段：FULL CURATION 完成】\n完整整理流程已完成，专家结果都已经过校验。"
                            + "你作为主 Agent 是唯一汇报口径：请只输出 TURN_DONE，并在 summary 给出面向管理员的最终汇报"
                            + "（结论、主要依据、已写入/未写入内容、待人工判断项），不要再调用任何专家、不要再发起完整整理。")));
            log.info("knowledge graph 节点推进 set_main_resume：子图完成，回主 Agent 汇总");
            return CompletableFuture.completedFuture(out);
        });

        // 结构化结果修复与恢复门（validate→repair→recovery 回路）：每个 Agent 的结果校验失败（解析或业务字段）不当作逃逸
        // Graph 的运行时异常，而是路由到对应 fix 节点：记录有界错误摘要、递增尝试次数，最多修复 2 次；仍无效时进入
        // 恢复门，标记 RECOVERY_REQUIRED 并把原因保留在可见说明中（§11.2 回路）。
        for (String role : FIXABLE) {
            String fixNode = FIX_PREFIX + role;
            String outputKey = OUTPUT_KEYS.get(role);
            graph.addNode(fixNode, (AsyncNodeAction) state -> {
                int attempt = integer(state, "retryAttempt");
                String error = validationErrorSummary(state, outputKey, role);
                Map<String, Object> out = new HashMap<>();
                out.put("retryAttempt", attempt + 1);
                out.put("lastValidatedNode", role);
                out.put("validationError", bounded(error, 400));
                String reconciliation = DRAFTER.equals(role)
                        ? "\n若上一轮你已成功写入草稿（工具回执显示新修订），请先调用 draft_read/workspace_document_list "
                          + "确认当前 revision，并把当前 revision 填入 DraftResult，不要重复写入相同内容。"
                        : "";
                out.put("messages", List.of(new UserMessage(
                        "【结构化结果无效，请修复】\n你刚输出的结构化结果校验失败，错误摘要：" + bounded(error, 400)
                                + "\n请按任务要求重新输出一份字段完整、严格符合 JSON 结构的全新结果，不要重复错误字段，"
                                + "不要输出解释文本。" + reconciliation)));
                log.info("knowledge graph 修复节点 {}：attempt={} error={}", fixNode, attempt + 1, bounded(error, 200));
                return CompletableFuture.completedFuture(out);
            });
            graph.addConditionalEdges(fixNode, fixRouter(), routes(role));
        }
        graph.addNode(RECOVERY_GATE, (AsyncNodeAction) state -> {
            Map<String, Object> out = new HashMap<>();
            out.put("turnMode", RECOVERY_MODE);
            out.put("recoveryInfo", "结构化结果在重试上限内仍无效，本轮已停止并保留 Checkpoint。"
                    + "错误摘要：" + bounded(stateText(state, "validationError"), 400));
            log.info("knowledge graph 恢复门 recovery_gate：重试耗尽");
            return CompletableFuture.completedFuture(out);
        });
        graph.addEdge(RECOVERY_GATE, TURN_FINISH);

        // 父 Graph 从主 Agent 进入，保证存在唯一会话级入口。
        graph.addEdge(StateGraph.START, MAIN_AGENT);
        graph.addConditionalEdges(MAIN_AGENT, mainRouter(), mainRoutes());
        graph.addEdge("set_main_resume", MAIN_AGENT);

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
        // 轮次完成节点指向主 Agent：turn_finish 边界暂停后的 Checkpoint nextNode 即为下一轮入口，
        // 下一轮 updateState(asNode=turn_finish) 据此恢复，而不会重跑本轮已完成节点。
        graph.addEdge(TURN_FINISH, MAIN_AGENT);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptAfter(MAIN_AGENT, COORDINATOR, RETRIEVER, DRAFTER, REVIEWER, "set_finish", TURN_FINISH)
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
                .addStrategy("mainTurnResult", KeyStrategy.REPLACE)
                // 会话级轮次字段：runId/turnMode/turnFinished 按轮注入与替换，不随消息追加。
                .addStrategy("turnResult", KeyStrategy.REPLACE)
                .addStrategy("conversationHistory", KeyStrategy.REPLACE)
                .addStrategy("historyTruncated", KeyStrategy.REPLACE)
                .addStrategy("recoveryInfo", KeyStrategy.REPLACE)
                .addStrategy("retryAttempt", KeyStrategy.REPLACE)
                .addStrategy("lastValidatedNode", KeyStrategy.REPLACE)
                .addStrategy("validationError", KeyStrategy.REPLACE)
                .addStrategy("runId", KeyStrategy.REPLACE)
                .addStrategy("turnMode", KeyStrategy.REPLACE)
                .addStrategy("turnFinished", KeyStrategy.REPLACE)
                .build();
    }

    /** 每轮会话历史裁剪：最多保留 4 轮；码点预算 8000，超预算时从最旧整轮丢弃。 */
    private static final int MAX_HISTORY_ROUNDS = 4;
    private static final int MAX_HISTORY_CODE_POINTS = 8000;

    /** @return 本轮回退的角色化会话历史（[用户指令, 最终回复] 交替）；从状态中读取历史并追加本轮，按轮次与码点裁剪。 */
    private List<Message> rebuildSessionHistory(com.alibaba.cloud.ai.graph.OverAllState state, boolean recoveryMode) {
        List<Message> history = new ArrayList<>();
        for (Object entry : asList(state.data().get("conversationHistory"))) {
            Message message = toSessionMessage(entry);
            if (message != null) {
                history.add(message);
            }
        }
        String roundUser = firstUserMessageText(state);
        String roundAssistant = recoveryMode ? stateText(state, "recoveryInfo") : mainSummaryText(state);
        if (roundUser != null && roundAssistant != null && !roundAssistant.isBlank()) {
            history.add(new UserMessage(roundUser));
            history.add(new AssistantMessage(roundAssistant));
        }
        // 从最新到最旧累计码点，超预算时丢弃最旧整轮（一条 User 及其后的 Assistant 视为一轮，半轮不截断）。
        int codePoints = 0;
        int removeFrom = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            int add = textCodePoints(history.get(index));
            if (codePoints + add > MAX_HISTORY_CODE_POINTS && history.size() - index > MAX_HISTORY_ROUNDS) {
                removeFrom = index + 1;
                break;
            }
            codePoints += add;
        }
        if (removeFrom > 0 && removeFrom % 2 == 1) {
            removeFrom--; // 整轮丢弃：偶数下标是 User，必须成对丢弃
        }
        return new ArrayList<>(history.subList(Math.min(removeFrom, history.size()), history.size()));
    }

    /** @return 裁剪是否实际发生（供 historyTruncated 投影）。 */
    private boolean truncatedFlag(com.alibaba.cloud.ai.graph.OverAllState state, List<Message> history) {
        int previous = asList(state.data().get("conversationHistory")).size();
        return previous > 0 && previous != history.size();
    }

    private List<Message> asList(Object value) {
        List<Message> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                Message message = toSessionMessage(entry);
                if (message != null) {
                    result.add(message);
                }
            }
        }
        return result;
    }

    /** @return 由序列化消息（Map 或 Message 对象）重建的角色化消息；角色未知时按 User/Assistant 交替推断。 */
    private Message toSessionMessage(Object entry) {
        String text;
        if (entry instanceof Message message) {
            text = sessionMessageText(message);
        } else if (entry instanceof String string) {
            text = string;
        } else {
            text = messageTextFromMap(entry instanceof Map<?, ?> map ? map : Map.of());
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        String role = null;
        if (entry instanceof Map<?, ?> map) {
            Object roleValue = map.get("messageType");
            if (roleValue == null) {
                roleValue = map.get("type");
            }
            role = String.valueOf(roleValue == null ? "" : roleValue);
        }
        if (entry instanceof Message message) {
            role = message.getMessageType().getValue();
        }
        if (role == null || role.isBlank()) {
            role = "";
        }
        return "USER".equalsIgnoreCase(role) ? new UserMessage(text)
                : "ASSISTANT".equalsIgnoreCase(role) ? new AssistantMessage(text) : null;
    }

    private static String sessionMessageText(Message message) {
        Object content = message.getText();
        if (content instanceof String text) {
            return text;
        }
        try {
            return String.valueOf(content);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** @return 本轮用户指令：messages 中第一条用户消息的正文（本轮注入的 goal）。 */
    private String firstUserMessageText(com.alibaba.cloud.ai.graph.OverAllState state) {
        for (Object entry : asList(state.data().get("messages"))) {
            String text = toSessionMessageText(entry);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String toSessionMessageText(Object entry) {
        if (entry instanceof String string) {
            return string;
        }
        if (entry instanceof Message message) {
            return message.getText() instanceof String text ? text : null;
        }
        if (entry instanceof Map<?, ?> map) {
            String text = messageTextFromMap(map);
            if (text == null) {
                return null;
            }
            return "assistant".equalsIgnoreCase(String.valueOf(map.get("messageType"))) ? null : text;
        }
        return null;
    }
    /** @return 主 Agent 本轮回退的可见回复文本；解析失败时返回原始文本（供历史消息展示）。 */
    private String mainSummaryText(com.alibaba.cloud.ai.graph.OverAllState state) {
        Object value = state.data().get("mainTurnResult");
        try {
            String text = value instanceof AssistantMessage message ? message.getText()
                    : value instanceof Map<?, ?> map ? messageTextFromMap(map)
                    : value == null ? null : String.valueOf(value);
            if (text == null || text.isBlank()) {
                return null;
            }
            return objectMapper.readValue(text, KnowledgeCurationGraphResult.MainTurnResult.class).summary();
        } catch (Exception exception) {
            return null;
        }
    }

    private static int textCodePoints(Message message) {
        String content = message.getText();
        return content == null ? 0 : content.codePointCount(0, content.length());
    }

    /** @return 修复回路的全部静态目标：fix_{agent} → fix_{agent}（路由器返回 FIX_<agent> 时的落点）。 */
    private static Map<String, String> fixEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String role : FIXABLE) {
            entries.put(FIX_PREFIX + role, FIX_PREFIX + role);
        }
        return entries;
    }

    private AsyncEdgeAction fixRouter() {
        return state -> CompletableFuture.completedFuture(
                integer(state, "retryAttempt") >= 2 ? "RECOVERY" : "REPAIR");
    }

    /** fix 节点的条件边：REPAIR（回到对应 Agent 重新生成）或 RECOVERY（进入恢复门）。 */
    private Map<String, String> routes(String role) {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("REPAIR", role);
        routes.put("RECOVERY", RECOVERY_GATE);
        return routes;
    }

    /** @return 指定结果键当前内容的解析错误摘要；解析成功但路由侧不满足业务规则时给出规则的概括说明。 */
    private String validationErrorSummary(com.alibaba.cloud.ai.graph.OverAllState state, String outputKey, String role) {
        Object value = state.data().get(outputKey);
        if (value == null) {
            return role + " 未输出结构化结果";
        }
        String text;
        if (value instanceof AssistantMessage message) {
            text = message.getText();
        } else if (value instanceof Map<?, ?> map) {
            text = messageTextFromMap(map);
        } else {
            text = String.valueOf(value);
        }
        try {
            objectMapper.readValue(text, OUTPUT_TYPES.get(role));
            return role + " 结构化结果不满足业务校验（缺少必要字段或可见回复）";
        } catch (Exception exception) {
            return "解析失败：" + bounded(String.valueOf(exception.getMessage()), 240);
        }
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private static String stateText(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state == null ? null : state.data().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 在已有消息列表（可能为 null）末尾追加一条消息，供状态推进节点统一合成上下文。 */
    private static List<?> appendTo(List<?> existing, Message message) {
        List<Object> messages = new ArrayList<>(existing == null ? List.of() : existing);
        messages.add(message);
        return messages;
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
        try {

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
    
        } catch (IllegalStateException exception) {
            return FIX_PREFIX + COORDINATOR;
        }
    }

    /** 草稿 Agent 条件边：BLOCKED 结束；WRITTEN 进入审查；WRITTEN 缺少修订标识时安全失败（§9）。 */
    String draftRoute(com.alibaba.cloud.ai.graph.OverAllState state) {
        try {

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
    
        } catch (IllegalStateException exception) {
            return FIX_PREFIX + DRAFTER;
        }
    }

    /** 审查 Agent 条件边：PASS/ASK_USER 结束；REVISE 未达上限返工，达到上限后结束并提示人工（§9）。 */
    String reviewRoute(com.alibaba.cloud.ai.graph.OverAllState state) {
        try {

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
    
        } catch (IllegalStateException exception) {
            return FIX_PREFIX + REVIEWER;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, String> mainRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("CHAT", TURN_FINISH);
        routes.put("TURN_DONE", TURN_FINISH);
        routes.put("FULL_CURATION", RETRIEVER);
        routes.putAll(fixEntries());
        return routes;
    }

    private AsyncEdgeAction mainRouter() {
        return state -> CompletableFuture.completedFuture(mainRoute(state));
    }

    /**
     * 主 Agent 条件边：只按 {@code MainTurnResult.action} 路由，不信任自然语言。
     * CHAT/TURN_DONE 必须携带面向用户的可见回复（§7 硬规则）；FULL_CURATION 交由完整整理流程。
     * 解析失败或业务校验不满足时进入修复回路（fix_main_agent），不逃逸 Graph。
     */
    String mainRoute(com.alibaba.cloud.ai.graph.OverAllState state) {
        try {
            KnowledgeCurationGraphResult.MainTurnResult result =
                    structured(state, "mainTurnResult", KnowledgeCurationGraphResult.MainTurnResult.class);
            if (result == null || result.action() == null) {
                throw new IllegalStateException("主 Agent 结构化结果无效");
            }
            String route = switch (result.action()) {
                case CHAT, TURN_DONE -> {
                    if (isBlank(result.summary())) {
                        throw new IllegalStateException("主 Agent 输出 " + result.action() + " 但没有可见回复");
                    }
                    yield result.action().name();
                }
                case FULL_CURATION -> "FULL_CURATION";
            };
            log.info("knowledge graph 条件边 mainRouter：action={} -> {}（expertCalls={}）",
                    result.action(), route, result.expertCalls());
            return route;
        } catch (IllegalStateException exception) {
            return FIX_PREFIX + MAIN_AGENT;
        }
    }

    private Map<String, String> coordinatorRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("CHAT", TURN_FINISH);
        routes.put("RETRIEVE", RETRIEVER);
        routes.put("DRAFT", "set_draft_context");
        routes.put("ASK_USER", "set_finish");
        routes.put("NO_CHANGE", "set_finish");
        routes.put("FINISH", "set_main_resume");
        routes.putAll(fixEntries());
        return routes;
    }

    private Map<String, String> draftRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("BLOCKED", "set_finish");
        routes.put("WRITTEN", "set_review_context");
        routes.putAll(fixEntries());
        return routes;
    }

    private Map<String, String> reviewRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("PASS", "set_finish");
        routes.put("ASK_USER", "set_finish");
        routes.put("REVISE", "set_draft_round");
        routes.put("REVISE_LIMIT", "set_finish");
        routes.putAll(fixEntries());
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
            // 模型长 JSON 输出存在把开头字段重复写在结尾的伪影（实测 candidateTargetDocumentId 在首尾各出现一次）。
            // Jackson 对 record 按构造器属性反序列化时，同一 creator 属性第二次出现会走进“已建对象后再 set”路径，
            // record 没有 setter/field 可回退，直接抛 InvalidDefinitionException 使整个 run 失败。
            // JsonNode 层面重复键是 last-wins 覆盖（不抛错），因此先 readTree 再去树转换，即可容忍重复键。
            return objectMapper.treeToValue(objectMapper.readTree(text), type);
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
            case MAIN_AGENT -> "你是知识整理会话的主 Agent，识别意图并按需调用专家、或发起完整整理流程。";
            case COORDINATOR -> "你是知识整理流程的调度者，只输出结构化决策。";
            case RETRIEVER -> "你是知识整理流程的检索者，只提交证据事实。";
            case DRAFTER -> "你是知识整理流程的写作者，只按已支持事实写入工作草稿。";
            case REVIEWER -> "你是知识整理流程的审查者，独立核对来源与草稿。";
            default -> "你是知识整理流程中的角色 Agent。";
        };
    }

    /** 供执行器使用：同一 threadId 驱动父 Graph 的 Checkpoint 配置。 */
    public static RunnableConfig graphConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }
}
