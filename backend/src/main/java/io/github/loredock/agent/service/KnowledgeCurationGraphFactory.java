package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.CompileConfig;
import io.github.loredock.agent.exception.ContextLimitExceededException;
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
import io.github.loredock.agent.model.enums.AgentNode;
import io.github.loredock.agent.model.request.ContextAssemblyRequest;
import io.github.loredock.agent.model.enums.ContextMode;
import io.github.loredock.agent.model.enums.ContextPurpose;
import io.github.loredock.agent.model.context.ContextSummaryState;
import io.github.loredock.agent.model.context.ConversationContext;
import io.github.loredock.agent.model.context.WorkflowContext;
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
 * <p>状态键与合并策略严格对应设计文档“Graph State”一节：消息键只作为下一 Agent 节点的一次性输入
 * 缓冲区（REPLACE，由准备节点写入组装结果），其余会话字段使用 {@code REPLACE}；Agent 子图内部由
 * 框架维护的 messages 仍为 APPEND（同一节点内 Tool 链连续累积），二者不可混为一谈。条件边不信任自然
 * 语言，路由节点通过 Jackson 解析前序专家节点写入 state 的结构化输出得到稳定路由依据；结构化输出无法
 * 解析或字段非法时按安全失败处理（见各 Route 方法）。</p>
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
    public static final String GRAPH_DEF_VERSION = "knowledge-curation-sess-v3";

    /** 启动时要求存在的角色，顺序与设计一致：主 Agent + 完整流程四个专家。 */
    public static final List<String> ROLES = List.of(MAIN_AGENT, COORDINATOR, RETRIEVER, DRAFTER, REVIEWER);

    /** 需要修复回路的 Agent：主 Agent 与完整整理链四个专家。 */
    public static final List<String> FIXABLE = List.of(MAIN_AGENT, COORDINATOR, RETRIEVER, DRAFTER, REVIEWER);

    /** 主 Agent：三个框架 AgentTool（retrieve/draft/review 专家）+ 记忆三工具（仅主 Agent 持有记忆层）。 */
    public static final List<String> MAIN_AGENT_TOOLS = List.of(
            "memory_search", "memory_read", "memory_write");

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

    /** 准备节点前缀：prep_{role}。节点入口前必经，把组装结果写入一次性 messages 缓冲区。 */
    public static final String PREP_PREFIX = "prep_";

    /** 摘要 Schema 版本（冗余地暴露给准备节点日志与恢复比对；权威常量在 ContextSummaryState）。 */
    public static final String SUMMARY_SCHEMA_VERSION = "v1";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCurationGraphFactory.class);

    private final ObjectMapper objectMapper;
    private final ContextAssemblyService assembly;

    /**
     * @param objectMapper 解析各专家结构化结果的 Jackson 实例
     * @param assembly 上下文组装服务（准备节点调用；按 agentNode × purpose 组装最小语义消息）
     */
    public KnowledgeCurationGraphFactory(ObjectMapper objectMapper, ContextAssemblyService assembly) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
        this.assembly = Objects.requireNonNull(assembly, "context assembly service");
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
    public static void validate(List<AgentSpec> specs, List<String> availableToolNames) {
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
        // 主 Agent：三个专家 AgentTool（工具名即专家名）+ 记忆三工具（只注册主 Agent，专家白名单不变）。
        List<ToolCallback> mainTools = new ArrayList<>(List.of(
                com.alibaba.cloud.ai.graph.agent.AgentTool.create(built.get(RETRIEVER)),
                com.alibaba.cloud.ai.graph.agent.AgentTool.create(built.get(DRAFTER)),
                com.alibaba.cloud.ai.graph.agent.AgentTool.create(built.get(REVIEWER))));
        mainTools.addAll(resolveTools(MAIN_AGENT_TOOLS, resolver));
        ReactAgent main = ReactAgent.builder()
                .name(MAIN_AGENT)
                .instruction(agents.instruction(MAIN_AGENT))
                .templateRenderer((template, params) -> template)
                .model(model)
                .tools(mainTools)
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
        // 状态推进节点：只推进 stage/轮次等结构化状态键，不再增量追加提示消息；
        // 各 Agent 入口的完整消息视图由准备节点按目的重建（含与 spec 约定一致的显式阶段标记）。
        graph.addNode("set_decide", (AsyncNodeAction) state -> {
            log.info("knowledge graph 节点推进 set_decide：stage -> DECIDE");
            return CompletableFuture.completedFuture(stateUpdateOnly("stage", "DECIDE"));
        });
        // 调度 Agent 的两条结束路径先推进到 FINISH 再回到调度 Agent 汇总；FINISH 标记由准备节点按 stage 生成。
        graph.addNode("set_finish", (AsyncNodeAction) state -> {
            log.info("knowledge graph 节点推进 set_finish：stage -> FINISH");
            return CompletableFuture.completedFuture(stateUpdateOnly("stage", "FINISH"));
        });
        // 审查返工节点：REVISE 未达上限时递增起草轮数再回到草稿 Agent，保证最多返工两轮（draftRound 最大 2）。
        graph.addNode("set_draft_round", (AsyncNodeAction) state -> {
            int round = integer(state, "draftRound") + 1;
            log.info("knowledge graph 节点推进 set_draft_round：REVISE 未达上限，draftRound -> {}（最大 2）", round);
            return CompletableFuture.completedFuture(stateUpdateOnly("draftRound", round));
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

        // 完整整理子图完成后回主 Agent 汇总：只标记主 Agent 的入口模式（REPORT），
        // 【当前阶段：FULL CURATION 完成】标记由 prep_main 按 purpose 生成。
        graph.addNode("set_main_resume", (AsyncNodeAction) state -> {
            log.info("knowledge graph 节点推进 set_main_resume：子图完成，回主 Agent 汇总");
            return CompletableFuture.completedFuture(stateUpdateOnly("mainMode", "REPORT"));
        });

        // 结构化结果修复与恢复门（validate→repair→recovery 回路）：每个 Agent 的结果校验失败（解析或业务字段）不当作逃逸
        // Graph 的运行时异常，而是路由到 fix 节点：记录有界错误摘要、递增尝试次数，最多修复 2 次；仍无效时进入
        // 恢复门，标记 RECOVERY_REQUIRED 并把原因保留在可见说明中（§11.2 回路）。
        // 修复提示不再写入 messages——准备节点按 purpose=REPAIR 统一重建（有界错误摘要 + 最小输入）。
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

        // 准备节点：每个 Agent 节点入口前必经，按 agentNode × purpose 组装最小语义消息，
        // 写入父 Graph 的一次性 messages 缓冲区（REPLACE），并回写摘要与压缩调用计数字段。
        for (String role : ROLES) {
            graph.addNode(PREP_PREFIX + role, prepareNode(role, model, toolContext));
        }

        // 父 Graph 从 prep_main 进入，保证存在唯一会话级入口。
        graph.addEdge(StateGraph.START, PREP_PREFIX + MAIN_AGENT);
        graph.addEdge(PREP_PREFIX + MAIN_AGENT, MAIN_AGENT);
        graph.addEdge(PREP_PREFIX + RETRIEVER, RETRIEVER);
        graph.addEdge(PREP_PREFIX + COORDINATOR, COORDINATOR);
        graph.addEdge(PREP_PREFIX + DRAFTER, DRAFTER);
        graph.addEdge(PREP_PREFIX + REVIEWER, REVIEWER);
        graph.addConditionalEdges(MAIN_AGENT, mainRouter(), mainRoutes());
        graph.addEdge("set_main_resume", PREP_PREFIX + MAIN_AGENT);

        graph.addConditionalEdges(COORDINATOR, coordinatorRouter(), coordinatorRoutes());
        graph.addEdge(RETRIEVER, "set_decide");
        graph.addEdge("set_decide", PREP_PREFIX + COORDINATOR);

        graph.addConditionalEdges(DRAFTER, draftRouter(), draftRoutes());
        graph.addConditionalEdges(REVIEWER, reviewRouter(), reviewRoutes());
        graph.addEdge("set_draft_round", PREP_PREFIX + DRAFTER);
        graph.addEdge("set_finish", PREP_PREFIX + COORDINATOR);
        // 轮次完成节点指向主 Agent 准备节点：turn_finish 边界暂停后的 Checkpoint nextNode 即为下一轮入口，
        // 下一轮 updateState(asNode=turn_finish) 据此恢复，而不会重跑本轮已完成节点。
        graph.addEdge(TURN_FINISH, PREP_PREFIX + MAIN_AGENT);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptAfter(MAIN_AGENT, COORDINATOR, RETRIEVER, DRAFTER, REVIEWER, "set_finish", TURN_FINISH)
                .build();
        return new GraphBundle(graph.compile(compileConfig), built, agents.definitions());
    }

    /**
     * 准备节点：按 {@code agentNode × purpose} 组装最小语义消息并写入父 Graph 的一次性 {@code messages}
     * 缓冲区（REPLACE）；同时回写摘要字段与压缩调用计数（若该轮发生压缩兜底）。
     *
     * <p>BLOCKED（组装后仍超限）以 {@link ContextLimitExceededException} 上抛，由执行器失败分类转为
     * WAITING_FOR_USER，不进入同输入重试回路。</p>
     */
    private AsyncNodeAction prepareNode(String role, ChatModel model, Map<String, Object> toolContext) {
        return (AsyncNodeAction) state -> {
            AgentNode node = toAgentNode(role);
            ContextPurpose purpose = purposeOf(state, role);
            ContextAssemblyRequest request = new ContextAssemblyRequest(
                    longOf(toolContext.get("conversationId")), longOf(toolContext.get("runId")),
                    node, purpose, currentInstruction(state), conversationContext(state),
                    workflowContext(state, purpose), assembly.budget(),
                    nullableLongOf(toolContext.get("projectId")));
            ContextAssemblyService.AssemblyResult result = assembly.assemble(
                    request, summaryState(state), integer(state, ContextAssemblyService.COMPRESSION_CALLS_KEY), model);
            if (result.prepared().receipt().mode() == ContextMode.BLOCKED) {
                throw new ContextLimitExceededException("知识整理节点入口上下文组装超限：agent=" + role
                        + " purpose=" + purpose + " 估算=" + result.prepared().receipt().estimatedInputTokens());
            }
            Map<String, Object> out = new HashMap<>(result.stateUpdates());
            out.put("messages", result.prepared().messages());
            log.info("知识整理准备节点 prep_{}：purpose={} mode={} 估算token={} 摘要={} 消息={} 条", role,
                    purpose, result.prepared().receipt().mode(), result.prepared().receipt().estimatedInputTokens(),
                    summaryState(state).hasSummary(), result.prepared().messages().size());
            return CompletableFuture.completedFuture(out);
        };
    }

    private static AgentNode toAgentNode(String role) {
        return switch (role) {
            case MAIN_AGENT -> AgentNode.MAIN_AGENT;
            case RETRIEVER -> AgentNode.RETRIEVER;
            case COORDINATOR -> AgentNode.COORDINATOR;
            case DRAFTER -> AgentNode.DRAFTER;
            case REVIEWER -> AgentNode.REVIEWER;
            default -> throw new IllegalArgumentException("未知角色：" + role);
        };
    }

    /** @return 当前节点入口的组装意图：修复回路优先；主 Agent 按主入口/汇总入口；Coordinator 按 DECIDE/FINISH 阶段。 */
    private ContextPurpose purposeOf(com.alibaba.cloud.ai.graph.OverAllState state, String role) {
        if (integer(state, "retryAttempt") > 0 && stateText(state, "validationError") != null) {
            return ContextPurpose.REPAIR;
        }
        return switch (role) {
            case MAIN_AGENT -> "REPORT".equals(stateText(state, "mainMode"))
                    ? ContextPurpose.FULL_CURATION_REPORT : ContextPurpose.CHAT;
            case RETRIEVER -> ContextPurpose.FULL_CURATION_RETRIEVE;
            case COORDINATOR -> "FINISH".equals(stateText(state, "stage"))
                    ? ContextPurpose.FULL_CURATION_FINISH : ContextPurpose.FULL_CURATION_DECIDE;
            case DRAFTER -> ContextPurpose.FULL_CURATION_DRAFT;
            case REVIEWER -> ContextPurpose.FULL_CURATION_REVIEW;
            default -> throw new IllegalArgumentException("未知角色：" + role);
        };
    }

    /** @return 当前轮用户指令（权威来源）；缺键时回退 goal（兼容旧 Checkpoint）。 */
    private static String currentInstruction(com.alibaba.cloud.ai.graph.OverAllState state) {
        String instruction = stateText(state, "currentInstruction");
        return instruction == null || instruction.isBlank() ? stateText(state, "goal") : instruction;
    }

    /** 会话上下文投影：角色化历史来自 conversationHistory（最近轮缓存），指导来自 pendingGuidance 键。 */
    private static ConversationContext conversationContext(com.alibaba.cloud.ai.graph.OverAllState state) {
        List<ConversationContext.DialogueTurn> turns = new ArrayList<>();
        for (Object entry : asList(state.data().get("conversationHistory"))) {
            Message message = toSessionMessage(entry);
            if (message != null) {
                String role = org.springframework.ai.chat.messages.MessageType.USER.equals(message.getMessageType())
                        ? "USER" : "ASSISTANT";
                turns.add(new ConversationContext.DialogueTurn(role,
                        message.getText() == null ? "" : message.getText()));
            }
        }
        ConversationContext.AdministratorGuidance pending = null;
        Object guidance = state.data().get("pendingGuidance");
        if (guidance instanceof Map<?, ?> map && map.get("text") != null && map.get("targetAgent") != null) {
            pending = new ConversationContext.AdministratorGuidance(
                    String.valueOf(map.get("targetAgent")), String.valueOf(map.get("text")));
        }
        return new ConversationContext(stateText(state, "goal"), turns, List.of(), pending,
                bool(state, "historyTruncated"));
    }

    /**
     * 当前轮工作上下文投影（设计文档 §5 矩阵）：只读取对应节点的权威字段，
     * 事实与引用来自结构化结果键（已规范化），不携带 Tool 原文。
     */
    private WorkflowContext workflowContext(com.alibaba.cloud.ai.graph.OverAllState state, ContextPurpose purpose) {
        KnowledgeCurationGraphResult.RetrievalResult retrieval =
                structured(state, "retrievalResult", KnowledgeCurationGraphResult.RetrievalResult.class);
        KnowledgeCurationGraphResult.CoordinatorResult coordination =
                structured(state, "coordinationResult", KnowledgeCurationGraphResult.CoordinatorResult.class);
        KnowledgeCurationGraphResult.DraftResult draft =
                structured(state, "draftResult", KnowledgeCurationGraphResult.DraftResult.class);
        KnowledgeCurationGraphResult.ReviewResult review =
                structured(state, "reviewResult", KnowledgeCurationGraphResult.ReviewResult.class);
        boolean includeFacts = purpose == ContextPurpose.FULL_CURATION_DECIDE
                || purpose == ContextPurpose.FULL_CURATION_DRAFT || purpose == ContextPurpose.FULL_CURATION_REVIEW;
        List<WorkflowContext.SupportedFact> facts = new ArrayList<>();
        List<WorkflowContext.UnresolvedQuestion> unresolved = new ArrayList<>();
        List<WorkflowContext.SourceReference> refs = new ArrayList<>();
        if (includeFacts && retrieval != null) {
            facts.addAll(retrieval.facts().stream().map(fact -> new WorkflowContext.SupportedFact(
                    fact.statement(), fact.sourceRefs().stream()
                    .map(source -> source.type().name() + ":" + source.id()).toList())).toList());
            for (int index = 0; index < retrieval.unresolvedQuestions().size(); index++) {
                unresolved.add(new WorkflowContext.UnresolvedQuestion("q" + index,
                        retrieval.unresolvedQuestions().get(index)));
            }
            refs.addAll(retrieval.facts().stream().flatMap(fact -> fact.sourceRefs().stream())
                    .map(source -> new WorkflowContext.SourceReference(source.type().name(), String.valueOf(source.id())))
                    .toList());
        }
        List<WorkflowContext.DraftReference> drafts = latestDrafts(draft);
        // 写入要求来自调度决策：draftInstruction 是写入内容要求；目录未在调度结果中结构化时留空（显示为“未指定”）。
        WorkflowContext.DraftInstruction instruction = coordination == null || isBlank(coordination.draftInstruction())
                ? null : new WorkflowContext.DraftInstruction("", coordination.draftInstruction());
        WorkflowContext.ReviewTarget target = purpose == ContextPurpose.FULL_CURATION_REVIEW && draft != null
                && !draft.drafts().isEmpty()
                ? new WorkflowContext.ReviewTarget(String.valueOf(draft.drafts().get(0).draftId()),
                        draft.drafts().get(0).revision().intValue())
                : null;
        WorkflowContext.RetryContext retry = purpose == ContextPurpose.REPAIR && stateText(state, "validationError") != null
                ? new WorkflowContext.RetryContext(integer(state, "retryAttempt"),
                        stateText(state, "lastValidatedNode"), stateText(state, "validationError"))
                : null;
        // REVISE 返工入口（draftRound>0）：只带本轮审查发现，不继承旧轮结论。
        List<WorkflowContext.ReviewFinding> findings = purpose == ContextPurpose.FULL_CURATION_DRAFT
                && draftRound(state) > 0 && review != null
                ? review.findings().stream().map(finding -> new WorkflowContext.ReviewFinding(
                        finding.code().name(), String.valueOf(finding.draftId()), finding.suggestion()))
                .toList() : List.of();
        return new WorkflowContext(facts, unresolved, refs, drafts, instruction, target, retry, findings);
    }

    /** @return 草稿结果中最新一条修订（draftId + revision），REVISE 与审查目标按此投影。 */
    private static List<WorkflowContext.DraftReference> latestDrafts(KnowledgeCurationGraphResult.DraftResult draft) {
        if (draft == null || draft.drafts().isEmpty()) {
            return List.of();
        }
        KnowledgeCurationGraphResult.DraftEntry entry =
                draft.drafts().get(draft.drafts().size() - 1);
        return List.of(new WorkflowContext.DraftReference(
                String.valueOf(entry.draftId()), entry.revision().intValue()));
    }

    /** @return 摘要状态字段快照（无摘要时返回空记录，由组装层按可重建语义处理）。 */
    private static ContextSummaryState summaryState(com.alibaba.cloud.ai.graph.OverAllState state) {
        String summary = stateText(state, "conversationSummary");
        String digest = stateText(state, "summarySourceDigest");
        if (summary == null && digest == null) {
            return new ContextSummaryState(null, 0L, null, null, 0);
        }
        return new ContextSummaryState(summary, longState(state, "summaryThroughMessageId"), digest,
                stateText(state, "summarySchemaVersion"), integer(state, "summaryGeneration"));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /** 可空长整型：ToolContext 中 projectId 只在项目侧会话写入（Map.of 不允许 null 值，执行器按需加入）。 */
    private static Long nullableLongOf(Object value) {
        if (value == null) {
            return null;
        }
        return longOf(value) > 0 ? longOf(value) : null;
    }

    private static long longState(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state == null ? null : state.data().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private int draftRound(com.alibaba.cloud.ai.graph.OverAllState state) {
        return integer(state, "draftRound");
    }

    private static boolean bool(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state == null ? null : state.data().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equals(String.valueOf(value));
    }

    /** @return 单键状态更新（供纯状态推进节点；messages 不再由这些节点增量注入）。 */
    private static Map<String, Object> stateUpdateOnly(String key, Object value) {
        Map<String, Object> out = new HashMap<>();
        out.put(key, value);
        return out;
    }

    private KeyStrategyFactory keyStrategies() {
        return KeyStrategy.builder()
                // messages 只作为下一 Agent 节点的一次性输入缓冲区：准备节点 REPLACE 组装结果，
                // Agent 子图内部由框架维护的 messages 仍为 APPEND（同一节点内 Tool 链连续累积）。
                .addStrategy("messages", KeyStrategy.REPLACE)
                .addStrategy("stage", KeyStrategy.REPLACE)
                .addStrategy("goal", KeyStrategy.REPLACE)
                .addStrategy("coordinationResult", KeyStrategy.REPLACE)
                .addStrategy("retrievalResult", KeyStrategy.REPLACE)
                .addStrategy("draftResult", KeyStrategy.REPLACE)
                .addStrategy("reviewResult", KeyStrategy.REPLACE)
                .addStrategy("draftRound", KeyStrategy.REPLACE)
                .addStrategy("finishReason", KeyStrategy.REPLACE)
                .addStrategy("mainTurnResult", KeyStrategy.REPLACE)
                .addStrategy("mainMode", KeyStrategy.REPLACE)
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
                // 上下文组装：本轮用户指令、待应用指导、摘要与压缩计数（REPLACE，由准备节点维护）。
                .addStrategy("currentInstruction", KeyStrategy.REPLACE)
                .addStrategy("pendingGuidance", KeyStrategy.REPLACE)
                .addStrategy("conversationSummary", KeyStrategy.REPLACE)
                .addStrategy("summaryThroughMessageId", KeyStrategy.REPLACE)
                .addStrategy("summarySourceDigest", KeyStrategy.REPLACE)
                .addStrategy("summarySchemaVersion", KeyStrategy.REPLACE)
                .addStrategy("summaryGeneration", KeyStrategy.REPLACE)
                .addStrategy(ContextAssemblyService.COMPRESSION_CALLS_KEY, KeyStrategy.REPLACE)
                .build();
    }

    /** 每轮会话历史裁剪：最多保留 4 轮；码点预算 8000，超预算时从最旧整轮丢弃。 */
    private static final int MAX_HISTORY_ROUNDS = 4;
    private static final int MAX_HISTORY_CODE_POINTS = 8000;

    /** @return 本轮回退的角色化会话历史（[用户指令, 最终回复] 交替）；用户侧以 currentInstruction 为权威来源，不扫描 messages。 */
    private List<Message> rebuildSessionHistory(com.alibaba.cloud.ai.graph.OverAllState state, boolean recoveryMode) {
        List<Message> history = new ArrayList<>();
        for (Object entry : asList(state.data().get("conversationHistory"))) {
            Message message = toSessionMessage(entry);
            if (message != null) {
                history.add(message);
            }
        }
        String roundUser = stateText(state, "currentInstruction");
        if (roundUser == null || roundUser.isBlank()) {
            // 存量 run 恢复缺 currentInstruction（旧 Checkpoint）时回退扫描 messages，保证历史不丢（设计文档 §7 回退语义）。
            roundUser = firstUserMessageText(state);
            if (roundUser != null) {
                log.warn("恢复旧 Checkpoint：缺少 currentInstruction，回退扫描消息缓冲区");
            }
        }
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

    private static List<Message> asList(Object value) {
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
    private static Message toSessionMessage(Object entry) {
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

    /** 修复条件边路由：REPAIR（经准备节点重新组装后回到对应 Agent）或 RECOVERY（进入恢复门）。 */
    private Map<String, String> routes(String role) {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("REPAIR", PREP_PREFIX + role);
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
        routes.put("FULL_CURATION", PREP_PREFIX + RETRIEVER);
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
        routes.put("RETRIEVE", PREP_PREFIX + RETRIEVER);
        routes.put("DRAFT", PREP_PREFIX + DRAFTER);
        routes.put("ASK_USER", "set_finish");
        routes.put("NO_CHANGE", "set_finish");
        routes.put("FINISH", "set_main_resume");
        routes.putAll(fixEntries());
        return routes;
    }

    private Map<String, String> draftRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("BLOCKED", "set_finish");
        routes.put("WRITTEN", PREP_PREFIX + REVIEWER);
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
        try {
            return tolerantStructured(objectMapper, state.data().get(key), type);
        } catch (IllegalStateException exception) {
            throw new IllegalStateException("Agent 结构化结果无法解析：" + key,
                    exception.getCause() == null ? exception : exception.getCause());
        }
    }

    /**
     * 宽容结构化解析（路由条件边与最终回复共用同一份容错，避免"路由能过、最终回复解析失败"的分叉）：
     * 兼容类型实例 / 消息 / 字符串 / Checkpoint 往返后的 Map 四种形态；先截取最外层 JSON 再 readTree→treeToValue。
     *
     * <ul>
     *   <li>重复键：模型长 JSON 输出存在把开头字段重复写在结尾的伪影（实测 candidateTargetDocumentId 两现）。
     *       Jackson 对 record 按构造器属性反序列化时，同一 creator 属性第二次出现会走进"已建对象后再 set"路径，
     *       record 没有 setter/field 可回退，直接抛 InvalidDefinitionException 使整个 run 失败；
     *       JsonNode 层面重复键是 last-wins 覆盖（不抛错），因此先 readTree 再去树转换。</li>
     *   <li>前后附加文字：模型常在 JSON 后补说明（本轮 run 60 即如此），先截取首尾括号再解析。</li>
     * </ul>
     *
     * @return 解析后的结构；找不到正文文本时返回 null
     */
    public static <T> T tolerantStructured(ObjectMapper objectMapper, Object value, Class<T> type) {
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
            text = value == null ? null : value.toString();
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(objectMapper.readTree(jsonObject(text)), type);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 结构化结果无法解析：" + type.getSimpleName(), exception);
        }
    }

    /** @return 截取最外层 JSON 对象（容忍模型在 JSON 前后附加说明性文字）。 */
    private static String jsonObject(String text) {
        String stripped = text.strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("结构化输出中未找到 JSON 对象");
        }
        return stripped.substring(start, end + 1);
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

    private static String text(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
        Object value = state.data().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(com.alibaba.cloud.ai.graph.OverAllState state, String key) {
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
