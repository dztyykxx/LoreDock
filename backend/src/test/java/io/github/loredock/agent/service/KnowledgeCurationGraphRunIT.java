package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.model.result.KnowledgeCurationGraphResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

/** 运行时探索：验证父 Graph 能否以 asNode + outputType + 条件边 + PostgresSaver 跑通 CHAT 短路。 */
@Testcontainers
class KnowledgeCurationGraphRunIT {

    private static final List<String> SPEC_FILES = List.of(
            "coordinator.md", "retriever.md", "drafter.md", "reviewer.md");
    private static final List<String> ALL_TOOLS = List.of(
            "selected_draft_list", "selected_draft_read", "knowledge_directory_list",
            "knowledge_document_list", "knowledge_document_read", "knowledge_grep",
            "knowledge_search", "workspace_document_list",
            "draft_create", "draft_read", "draft_update", "draft_rename", "draft_diff");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_graph_run").withUsername("loredock").withPassword("loredock_test");

    private static String schema = "graph_run_" + System.nanoTime();

    @AfterEach
    void dropSchema() {
        try (var conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = conn.createStatement()) {
            if (schema.startsWith("graph_run_")) {
                st.execute("drop schema if exists " + schema + " cascade");
            }
        } catch (Exception ignore) {
        }
    }

    @Test
    void chatShortCircuitsAfterCoordinatorWithoutRetrieve() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        DataSource ds = dataSource();
        PostgresSaver saver = PostgresSaver.builder().datasource(ds)
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"stage\":\"START\",\"action\":\"CHAT\",\"reason\":\"问候\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"你好，我在线。\"}")));
        KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());
        List<AgentSpec> specs = loadSpecs();
        factory.validate(specs, ALL_TOOLS);
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationGraphRunIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                Map.of("operatorId", "admin", "projectIdentifier", "atlas",
                        "conversationId", 1L, "runId", 2L),
                saver,
                List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());
        RunnableConfig config = RunnableConfig.builder().threadId("curation-chat").build();
        Map<String, Object> initial = Map.of(
                "goal", "整理项目知识",
                "stage", "START",
                "draftRound", 0,
                "messages", List.of(new UserMessage("你好")));

        Flux<NodeOutput> stream = bundle.graph().stream(initial, config);
        List<NodeOutput> outputs = stream.collectList().block(Duration.ofSeconds(20));

        var state = bundle.graph().getState(config);
        Map<String, Object> data = state == null || state.state() == null ? Map.of() : state.state().data();
        Object coordination = data.get("coordinationResult");
        assertThat(outputs).isNotNull();
        // 闲聊短路只运行调度 Agent：总模型调用 1 次，且不产生检索、草稿、审查的专家结果键。
        assertThat(model.calls()).isEqualTo(1);
        assertThat(data).containsKeys("messages", "goal", "stage", "draftRound", "coordinationResult");
        assertThat(data).doesNotContainKeys("retrievalResult", "draftResult", "reviewResult");
        assertThat(coordination).isInstanceOf(AssistantMessage.class);
        String text = ((AssistantMessage) coordination).getText();
        assertThat(text).contains("\"action\":\"CHAT\"").contains("\"summary\":\"你好，我在线。\"");
        System.out.printf("测试证据：场景=普通闲聊短路，模型调用=%d，专家结果键=0，最终回复=%s%n",
                model.calls(), "你好，我在线。");
    }

    @Test
    void coordinatorReceivesGoalAsUserMessage() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        DataSource ds = dataSource();
        PostgresSaver saver = PostgresSaver.builder().datasource(ds)
                .createOption(CreateOption.CREATE_NONE).build();

        // 目标/goal 作为用户消息注入 messages；脚本化模型捕获真实 prompt，验证调度 Agent 能看到“提交的文档/目标”。
        // 这是对“调度 Agent 看不到提交文档而把整理误判为 CHAT”bug 的直接回归保护：asNode(true,false) 之前该断言会失败。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"stage\":\"START\",\"action\":\"CHAT\",\"reason\":\"问候\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"你好，我在线。\"}")));
        KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());
        List<AgentSpec> specs = loadSpecs();
        factory.validate(specs, ALL_TOOLS);
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationGraphRunIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                Map.of("operatorId", "admin", "projectIdentifier", "atlas",
                        "conversationId", 1L, "runId", 2L),
                saver,
                List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());
        RunnableConfig config = RunnableConfig.builder().threadId("curation-see-goal").build();
        String goal = "将勾选草稿合并为一份稳定的业务知识";
        Map<String, Object> initial = Map.of(
                "goal", goal,
                "stage", "START",
                "draftRound", 0,
                "messages", List.of(new UserMessage(goal)));

        bundle.graph().stream(initial, config).collectList().block(Duration.ofSeconds(20));

        // 调度 Agent 收到的模型 prompt 必须包含本轮目标，否则它无法判断这是知识整理任务而会误判为闲聊。
        assertThat(model.prompts()).isNotEmpty();
        assertThat(model.prompts().get(0)).contains(goal);
        System.out.printf("测试证据：场景=调度Agent看到提交目标，prompt含目标=%s，模型调用=%d%n",
                model.prompts().get(0).contains(goal), model.calls());
    }

    @Test
    void draftThenReviewPassRoutesThroughAllFourAgents() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // 按 Agent 识别脚本化模型：每个 Agent 的指令正文含唯一名称，调度 Agent 进入 3 次（START/DECIDE/FINISH），
        // 其余各一次；按“角色 → 到该角色的第几次调用”返回对应结构化输出，避免跨 Agent 全局序号错位。
        AgentAwareChatModel model = new AgentAwareChatModel(Map.of(
                "coordinator", List.of(
                        "{\"stage\":\"START\",\"action\":\"RETRIEVE\",\"reason\":\"需整理\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"开始整理\"}",
                        "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                                + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"决定起草\"}",
                        "{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}"),
                "retriever", List.of(
                        "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                                + "\"facts\":[{\"statement\":\"新增背景\",\"support\":\"SUPPORTED\","
                                + "\"sourceRefs\":[{\"type\":\"EVIDENCE\",\"id\":88}]}],"
                                + "\"unresolvedQuestions\":[],\"summary\":\"检索到背景事实\"}"),
                "drafter", List.of(
                        "{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                                + "\"question\":null,\"summary\":\"已写入\"}"),
                "reviewer", List.of(
                        "{\"verdict\":\"PASS\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                                + "\"findings\":[],\"question\":null,\"summary\":\"审查通过\"}")));
        KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());
        List<AgentSpec> specs = loadSpecs();
        factory.validate(specs, ALL_TOOLS);
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationGraphRunIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                Map.of("operatorId", "admin", "projectIdentifier", "atlas",
                        "conversationId", 1L, "runId", 2L),
                saver,
                List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());
        RunnableConfig config = RunnableConfig.builder().threadId("curation-draft").build();
        Map<String, Object> initial = Map.of(
                "goal", "整理项目知识", "stage", "START", "draftRound", 0,
                "messages", List.of(new UserMessage("请整理背景")));

        // 循环：首次用 threadId 配置，之后用上一节点运行返回的 checkpoint 配置（含 checkPointId/nextNode）续跑，直到终态。
        List<NodeOutput> outputs = new ArrayList<>();
        Map<String, Object> input = initial;
        RunnableConfig resumeConfig = config;
        int rounds = 0;
        while (rounds++ < 20) {
            List<NodeOutput> batch = bundle.graph().stream(input, resumeConfig).collectList().block(Duration.ofSeconds(20));
            outputs.addAll(batch);
            var snapshot = bundle.graph().getState(config);
            String next = snapshot == null ? null : snapshot.next();
            if (next == null || "__END__".equals(next)) {
                break;
            }
            input = Map.of();
            resumeConfig = snapshot.config();
        }

        // 完整路径：调度 3 次 + 检索/草稿/审查各 1 次 = 6 次模型调用；四个结构化结果键全部落库，draftRound 保持 0（未返工）。
        var data = bundle.graph().getState(config) == null || bundle.graph().getState(config).state() == null
                ? Map.<String, Object>of() : bundle.graph().getState(config).state().data();
        assertThat(outputs).isNotNull();
        assertThat(model.totalCalls()).isEqualTo(6);
        assertThat(data).containsKeys("retrievalResult", "coordinationResult", "draftResult", "reviewResult");
        assertThat(data.get("draftRound")).isEqualTo(0);
        assertThat(((AssistantMessage) data.get("reviewResult")).getText()).contains("\"verdict\":\"PASS\"");
        System.out.printf("测试证据：场景=完整整理路径，模型调用=%d，四专家结果键=%d，draftRound=%d，resume轮=%d%n",
                model.totalCalls(), 4, data.get("draftRound"), rounds);
    }

    @Test
    void reworkStopsAtTwoRoundsThroughGraph() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();
        String finding = "{\"code\":\"UNRESOLVED_CONFLICT\",\"draftId\":19,\"description\":\"仍有冲突\",\"suggestion\":\"补充来源\"}";
        // 审查者始终 REVISE，调度者 3 次进入；draftRound 在 set_draft_round 递增到 2 后应进入 REVISE_LIMIT 停止返工。
        AgentAwareChatModel model = new AgentAwareChatModel(Map.of(
                "coordinator", List.of(
                        "{\"stage\":\"START\",\"action\":\"RETRIEVE\",\"reason\":\"需整理\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"开始整理\"}",
                        "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                                + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"决定起草\"}",
                        "{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"达到返工上限\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已到返工上限，交由人工\"}"),
                "retriever", List.of(
                        "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                                + "\"facts\":[{\"statement\":\"背景\",\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],"
                                + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}"),
                "drafter", List.of(
                        "{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                                + "\"question\":null,\"summary\":\"已写入\"}",
                        "{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":4,\"operation\":\"MODIFY\"}],"
                                + "\"question\":null,\"summary\":\"已改写\"}",
                        "{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":5,\"operation\":\"MODIFY\"}],"
                                + "\"question\":null,\"summary\":\"再改写\"}"),
                "reviewer", List.of(
                        "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                                + "\"findings\":[" + finding + "],\"question\":null,\"summary\":\"返工\"}",
                        "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":4}],"
                                + "\"findings\":[" + finding + "],\"question\":null,\"summary\":\"再返工\"}",
                        "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":5}],"
                                + "\"findings\":[" + finding + "],\"question\":null,\"summary\":\"达到上限\"}")));
        KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());
        List<AgentSpec> specs = loadSpecs();
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationGraphRunIT::tool, (a, b) -> a, LinkedHashMap::new));
        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model,
                new StaticToolCallbackResolver(List.copyOf(callbacks.values())),
                Map.of("operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 1L, "runId", 2L),
                saver, List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());
        RunnableConfig config = RunnableConfig.builder().threadId("curation-rework").build();
        Map<String, Object> initial = Map.of("goal", "整理", "stage", "START", "draftRound", 0,
                "messages", List.of(new UserMessage("请整理")));
        RunnableConfig resumeConfig = config;
        Map<String, Object> input = initial;
        for (int rounds = 0; rounds < 20; rounds++) {
            bundle.graph().stream(input, resumeConfig).collectList().block(Duration.ofSeconds(20));
            var snapshot = bundle.graph().getState(config);
            String next = snapshot == null ? null : snapshot.next();
            if (next == null || "__END__".equals(next)) break;
            input = Map.of();
            resumeConfig = snapshot.config();
        }
        var data = bundle.graph().getState(config).state().data();
        // 审查持续 REVISE 时最多返工两轮：draftRound 被钳制在 2，且不再进入第三轮草稿（drafter 只执行 3 次）。
        assertThat(data.get("draftRound")).isEqualTo(2);
        assertThat(model.callsByAgent("drafter")).isEqualTo(3);
        assertThat(model.callsByAgent("reviewer")).isEqualTo(3);
        System.out.printf("测试证据：场景=审查持续返工，最终draftRound=%d，草稿Agent执行=%d，达到上限后停止返工%n",
                data.get("draftRound"), model.callsByAgent("drafter"));
    }

    @Test
    void composesStageContextIntoEachAgentPrompt() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // 完整路径（调度 3 次 + 检索/草稿/审查各 1 次），脚本化模型按角色返回固定结构化结果。
        AgentAwareChatModel model = new AgentAwareChatModel(Map.of(
                "coordinator", List.of(
                        "{\"stage\":\"START\",\"action\":\"RETRIEVE\",\"reason\":\"需整理\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"开始整理\"}",
                        "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                                + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"决定起草\"}",
                        "{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}"),
                "retriever", List.of(
                        "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                                + "\"facts\":[{\"statement\":\"新增背景\",\"support\":\"SUPPORTED\","
                                + "\"sourceRefs\":[{\"type\":\"EVIDENCE\",\"id\":88}]}],"
                                + "\"unresolvedQuestions\":[],\"summary\":\"检索到背景事实\"}"),
                "drafter", List.of(
                        "{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                                + "\"question\":null,\"summary\":\"已写入\"}"),
                "reviewer", List.of(
                        "{\"verdict\":\"PASS\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                                + "\"findings\":[],\"question\":null,\"summary\":\"审查通过\"}")));
        KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());
        List<AgentSpec> specs = loadSpecs();
        factory.validate(specs, ALL_TOOLS);
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationGraphRunIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                Map.of("operatorId", "admin", "projectIdentifier", "atlas",
                        "conversationId", 1L, "runId", 2L),
                saver,
                List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());
        RunnableConfig config = RunnableConfig.builder().threadId("curation-context").build();
        Map<String, Object> initial = Map.of("goal", "整理项目知识", "stage", "START", "draftRound", 0,
                "messages", List.of(new UserMessage("请整理背景")));

        RunnableConfig resumeConfig = config;
        Map<String, Object> input = initial;
        for (int rounds = 0; rounds < 20; rounds++) {
            bundle.graph().stream(input, resumeConfig).collectList().block(Duration.ofSeconds(20));
            var snapshot = bundle.graph().getState(config);
            String next = snapshot == null ? null : snapshot.next();
            if (next == null || "__END__".equals(next)) break;
            input = Map.of();
            resumeConfig = snapshot.config();
        }

        // 每一环 Agent 的模型 prompt 必须带有服务端合成的、带标签的前序结果（而非只有原始 JSON 或只有 goal），
        // 以便调度 Agent 可靠识别所处阶段、其他 Agent 直接使用已给事实而不再重复检索：
        // 调度 DECIDE 看到【检索结果】、草稿看到【调度决策·草稿写入要求】、审查看到【草稿结果·本次修订】、调度 FINISH 看到【审查结果】。
        List<String> coordinator = model.prompts("coordinator");
        assertThat(coordinator).hasSize(3);
        assertThat(coordinator.get(1)).contains("【检索结果");
        assertThat(model.prompts("drafter").get(0)).contains("【调度决策 · 草稿写入要求】");
        assertThat(model.prompts("reviewer").get(0)).contains("【草稿结果 · 本次修订】");
        assertThat(coordinator.get(2)).contains("【审查结果】");
        System.out.printf("测试证据：场景=服务端按阶段合成带标签上下文，coordinator进入=%d，DECIDE含检索标签=%s，草稿含决策标签=%s，审查含草稿标签=%s，FINISH含审查标签=%s%n",
                coordinator.size(),
                coordinator.get(1).contains("【检索结果"),
                model.prompts("drafter").get(0).contains("【调度决策 · 草稿写入要求】"),
                model.prompts("reviewer").get(0).contains("【草稿结果 · 本次修订】"),
                coordinator.get(2).contains("【审查结果】"));
    }

    @Test
    void injectsExplicitFinishStageMarkerOnNoChangePath() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // NO_CHANGE 路径：检索后调度判定“无需修改”→ set_finish → 调度 FINISH。因为没有草稿/审查，
        // 若只靠标签数量判断阶段，调度 Agent 会误判为 DECIDE 甚至输出闲聊。验证 set_finish 注入的【当前阶段：FINISH】标记。
        AgentAwareChatModel model = new AgentAwareChatModel(Map.of(
                "coordinator", List.of(
                        "{\"stage\":\"START\",\"action\":\"RETRIEVE\",\"reason\":\"需整理\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"开始整理\"}",
                        "{\"stage\":\"DECIDE\",\"action\":\"NO_CHANGE\",\"reason\":\"已覆盖\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"无需修改\"}",
                        "{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                                + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}"),
                "retriever", List.of(
                        "{\"issueType\":\"NONE\",\"candidateTargetDocumentId\":710004,"
                                + "\"facts\":[],\"unresolvedQuestions\":[],\"summary\":\"无缺口\"}")));
        KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());
        List<AgentSpec> specs = loadSpecs();
        factory.validate(specs, ALL_TOOLS);
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationGraphRunIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                Map.of("operatorId", "admin", "projectIdentifier", "atlas",
                        "conversationId", 1L, "runId", 2L),
                saver, List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());
        RunnableConfig config = RunnableConfig.builder().threadId("curation-no-change").build();
        Map<String, Object> initial = Map.of("goal", "整理项目知识", "stage", "START", "draftRound", 0,
                "messages", List.of(new UserMessage("请整理背景")));

        RunnableConfig resumeConfig = config;
        Map<String, Object> input = initial;
        for (int rounds = 0; rounds < 20; rounds++) {
            bundle.graph().stream(input, resumeConfig).collectList().block(Duration.ofSeconds(20));
            var snapshot = bundle.graph().getState(config);
            String next = snapshot == null ? null : snapshot.next();
            if (next == null || "__END__".equals(next)) break;
            input = Map.of();
            resumeConfig = snapshot.config();
        }

        // 关键回归：NO_CHANGE 路径的 FINISH 阶段必须收到明确的【当前阶段：FINISH】指令，否则调度 Agent 会误判阶段。
        List<String> coordinator = model.prompts("coordinator");
        assertThat(coordinator).hasSize(3);
        assertThat(coordinator.get(2)).contains("【当前阶段：FINISH】");
        System.out.printf("测试证据：场景=NO_CHANGE路径FINISH带阶段标记，coordinator进入=%d，FINISH含阶段标记=%s%n",
                coordinator.size(), coordinator.get(2).contains("【当前阶段：FINISH】"));
    }

    private DataSource dataSource() {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private List<AgentSpec> loadSpecs() throws Exception {
        return SPEC_FILES.stream().map(n -> readSpec("agent-specs/knowledge-curation/" + n)).toList();
    }

    private AgentSpec readSpec(String path) {
        try {
            return AgentSpecLoader.loadFromResource(new ClassPathResource(path));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ToolCallback tool(String name) {
        return FunctionToolCallback.builder(name, (EchoInput i) -> i.value())
                .description("测试工具").inputType(EchoInput.class).build();
    }

    private record EchoInput(String value) { }

    private static ChatResponse answer(String json) {
        ChatResponseMetadata meta = ChatResponseMetadata.builder().build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))), meta);
    }

    /** 按指令正文里的角色名识别当前 Agent，并对每个角色按“第几次进入”返回对应的结构化输出。 */
    private static final class AgentAwareChatModel implements ChatModel {
        private final Map<String, List<String>> agentResponses;
        private final Map<String, AtomicInteger> counters = new LinkedHashMap<>();
        private final Map<String, List<String>> promptsByAgent = new LinkedHashMap<>();
        private final AtomicInteger total = new AtomicInteger();

        private AgentAwareChatModel(Map<String, List<String>> agentResponses) {
            this.agentResponses = agentResponses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String agent = detectAgent(prompt.getContents());
            int index = counters.computeIfAbsent(agent, ignored -> new AtomicInteger()).getAndIncrement();
            promptsByAgent.computeIfAbsent(agent, ignored -> new ArrayList<>())
                    .add(prompt.getContents());
            List<String> responses = agentResponses.get(agent);
            String json = responses.get(Math.min(index, responses.size() - 1));
            total.incrementAndGet();
            return answer(json);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }

        int totalCalls() { return total.get(); }

        int callsByAgent(String agent) {
            AtomicInteger counter = counters.get(agent);
            return counter == null ? 0 : counter.get();
        }

        /** @return 该角色各次进入时收到的完整模型 prompt 内容（用于断言前序结构化结果已注入上下文）。 */
        List<String> prompts(String agent) {
            return promptsByAgent.getOrDefault(agent, List.of());
        }

        private static String detectAgent(String contents) {
            if (contents == null) return "unknown";
            if (contents.contains("（coordinator）")) return "coordinator";
            if (contents.contains("（retriever）")) return "retriever";
            if (contents.contains("（drafter）")) return "drafter";
            if (contents.contains("（reviewer）")) return "reviewer";
            return "unknown";
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        private ScriptedChatModel(List<ChatResponse> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            return responses.get(Math.min(calls.getAndIncrement(), responses.size() - 1));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        int calls() { return calls.get(); }
        List<String> prompts() { return List.copyOf(prompts); }
    }
}
