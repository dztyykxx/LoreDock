package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 上下文组装集成测试（tasks 1.3-5.1）：准备节点最小上下文与 REVISE 基线、直调父侧输入、
 * 组装 BLOCKED → WAITING_FOR_USER（0 业务调用）、确定性压缩与 LLM 压缩兜底 + 摘要复用。
 */
@Testcontainers
class KnowledgeCurationContextAssemblyIT {

    private static final List<String> SPEC_FILES = List.of(
            "main_agent.md", "coordinator.md", "retriever.md", "drafter.md", "reviewer.md");
    private static final List<String> ALL_TOOLS = List.of(
            "selected_draft_list", "selected_draft_read", "knowledge_directory_list",
            "knowledge_document_list", "knowledge_document_read", "knowledge_grep",
            "knowledge_search", "workspace_document_list",
            "draft_create", "draft_read", "draft_update", "draft_rename", "draft_diff");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_context_assembly").withUsername("loredock").withPassword("loredock_test");

    private static String schema = "context_assembly_" + System.nanoTime();

    /** 业务目的：完整整理子图各专家只接收准备节点组装的最小上下文，REVISE 返工时 Drafter 可见基线 revision 与审查发现。 */
    @Test
    void fullRoundUsesMinimalContextsAndReviseBaseline() throws Exception {
        migrate();
        RecordingModel model = new RecordingModel(List.of(
                answer("{\"action\":\"FULL_CURATION\",\"summary\":\"开始整理\",\"expertCalls\":[]}"),
                answer(retrievalJson()),
                answer("{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                        + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"决定起草\"}"),
                answer("{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                        + "\"question\":null,\"summary\":\"已写入\"}"),
                answer("{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[{\"code\":\"UNRESOLVED_CONFLICT\",\"draftId\":19,"
                        + "\"description\":\"缺少依据\",\"suggestion\":\"补充引用\"}],"
                        + "\"question\":null,\"summary\":\"需要修订\"}"),
                answer("{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":4,\"operation\":\"MODIFY\"}],"
                        + "\"question\":null,\"summary\":\"已修订\"}"),
                answer("{\"verdict\":\"PASS\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":4}],"
                        + "\"findings\":[],\"question\":null,\"summary\":\"审查通过\"}"),
                answer("{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}"),
                answer("{\"action\":\"TURN_DONE\",\"summary\":\"整理完成。\",\"expertCalls\":[]}")));

        executor(model, saverWithoutSchema()).start(run(11L, "knowledge-task-conversation-11"),
                "将勾选草稿合并为知识", definition());

        List<String> prompts = model.prompts();
        assertThat(prompts).hasSize(9);
        assertThat(prompts.get(3)).contains("【写入要求】");
        assertThat(prompts.get(3)).doesNotContain("【本轮审查发现】");
        assertThat(prompts.get(2)).contains("【当前阶段：DECIDE】");
        assertThat(prompts.get(5)).contains("draftRef: 19 revision=3");
        assertThat(prompts.get(5)).contains("【本轮审查发现】");
        assertThat(prompts.get(6)).contains("【审查目标】");
        // 任何模型输入不得携带原始结构化 JSON 或工具链标记（矩阵禁止继承内容）。
        assertThat(prompts).noneMatch(prompt -> prompt.contains("\"issueType\":\"MISSING\"")
                || prompt.contains("toolCallId"));
        System.out.printf("测试证据：场景=完整子图最小上下文，Drafter二次进入含基线=%s，无原始JSON=%s%n",
                prompts.get(5).contains("draftRef: 19 revision=3"),
                prompts.stream().noneMatch(p -> p.contains("toolCallId")));
    }

    /** 业务目的：直调路径专家只接收主 Agent 生成的 actualInput 文本，不读取父图会话状态。 */
    @Test
    void directDraftExpertReceivesParentAssembledTextOnly() throws Exception {
        migrate();
        RecordingModel model = new RecordingModel(List.of(
                toolCallAnswer("drafter", "{\"input\":\"任务：仅调整错误处理章节；draftId=14；baseRevision=3；decisionIds=[decision-8]\"}"),
                answer("{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":14,\"revision\":4,\"operation\":\"MODIFY\"}],"
                        + "\"question\":null,\"summary\":\"已改标题\"}"),
                answer("{\"action\":\"TURN_DONE\",\"summary\":\"已修改、未经专家审查。\",\"expertCalls\":[\"drafter\"]}")));

        executor(model, saverWithoutSchema()).start(run(12L, "knowledge-task-conversation-12"),
                "只调整草稿 14 的错误处理章节", definition());

        assertThat(model.calls()).isEqualTo(3);
        assertThat(model.prompts().get(1)).contains("任务：仅调整错误处理章节");
        // 专家侧看不到主 Agent 的组装声明与会话上下文（崭新子线程 state，设计 §4.2）。
        assertThat(model.prompts().get(1)).doesNotContain("【上下文】agentNode");
        assertThat(model.prompts().get(1)).doesNotContain("只调整草稿 14 的错误处理章节");
        System.out.printf("测试证据：场景=直调专家输入为主Agent实际文本，含任务=%s，不含父侧上下文=%s%n",
                model.prompts().get(1).contains("任务：仅调整错误处理章节"),
                !model.prompts().get(1).contains("【上下文】agentNode"));
    }

    /** 业务目的：节点入口组装 BLOCKED 时 run 转 WAITING_FOR_USER，业务模型调用数为 0（预算守卫不发送请求）。 */
    @Test
    void blockedAssemblyMarksRunWaitingWithoutAnyModelCall() throws Exception {
        migrate();
        RecordingModel model = new RecordingModel(List.of(
                answer("{\"action\":\"CHAT\",\"summary\":\"不应出现\",\"expertCalls\":[]}")));
        ContextBudget tiny = new ContextBudget(20, 10, 5, 5, 5, 3, 1000, 0, 3);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        when(runs.markKnowledgeRunning(eq(13L), any())).thenReturn(1);
        when(runs.markKnowledgeRecovery(eq(13L), any())).thenReturn(1);
        KnowledgeCurationRunExecutor executor = executor(model, saverWithoutSchema(), tiny, runs, messages);

        AgentRunEntity run = run(13L, "knowledge-task-conversation-13");
        run.setAgentName("knowledge-curator");
        executor.start(run, "任意目标", definition());

        assertThat(model.calls()).isZero();
        verify(runs).markKnowledgeRecovery(eq(13L), any(Instant.class));
        verify(messages).insert(org.mockito.ArgumentMatchers.<KnowledgeTaskMessageEntity>argThat(
                entity -> entity.getContent().contains("预算")));
        System.out.printf("测试证据：场景=组装BLOCKED转WAITING，业务模型调用=%d%n", model.calls());
    }

    /** 业务目的：确定性压缩在真实 Graph 中丢弃最旧完整轮次、保留最新轮，结果不含最旧轮标记。 */
    @Test
    void deterministicTrimDropsOldestRoundsInRealGraph() throws Exception {
        migrate();
        ContextBudget budget = new ContextBudget(10000, 2000, 100, 50, 300, 250, 5000, 1, 3);
        RecordingModel model = new RecordingModel(List.of(
                answer("{\"action\":\"CHAT\",\"summary\":\"已在。\",\"expertCalls\":[]}")));
        CompiledGraph graph = graphWith(new ObjectMapper(), mock(KnowledgeTaskMessageMapper.class),
                mock(KnowledgeTaskConversationMapper.class), model, budget);

        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("goal", "工作目标");
        initial.put("stage", "START");
        initial.put("draftRound", 0);
        initial.put("currentInstruction", "本轮指令");
        initial.put("conversationHistory", chatHistory(8, "旧轮%d", 60));
        graph.stream(initial, RunnableConfig.builder().threadId("ctx-trim1").build())
                .collectList().block(Duration.ofSeconds(20));

        String prompt = model.prompts().get(0);
        assertThat(prompt).doesNotContain("旧轮0");
        assertThat(prompt).contains("旧轮7");
        System.out.printf("测试证据：场景=确定性压缩，最旧轮被丢弃=%s，最新轮保留=%s%n",
                !prompt.contains("旧轮0"), prompt.contains("旧轮7"));
    }

    /** 业务目的：确定性压缩后仍超限时经一层受限 LLM 压缩（摘要+最近窗口）放行；业务输入含会话摘要。 */
    @Test
    void llmCompressionFitsAndSummaryIncluded() throws Exception {
        migrate();
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        KnowledgeTaskConversationMapper conversations = mock(KnowledgeTaskConversationMapper.class);
        // 两轮旧轮（user + final reply，subject 匹配 targetSkill），供压缩读取与摘要 digest。
        List<KnowledgeTaskMessageEntity> oldTurns = oldTurnRows(400L, "knowledge-curator", 2);
        when(messages.selectList(any())).thenReturn(oldTurns);
        KnowledgeTaskConversationEntity conversation = new KnowledgeTaskConversationEntity();
        conversation.setTargetSkill("knowledge-curator");
        when(conversations.selectOne(any())).thenReturn(conversation);

        ContextBudget budget = new ContextBudget(10000, 1200, 100, 50, 600, 400, 5000, 2, 3);
        RecordingModel model = new RecordingModel(List.of(
                answer("{\"summary\":\"前两轮已完成：目标无变化。\","
                        + "\"retainedReferenceIds\":[],\"retainedDecisionIds\":[],"
                        + "\"retainedQuestionIds\":[]}"),
                answer("{\"action\":\"CHAT\",\"summary\":\"根据会话状态直接回答。\",\"expertCalls\":[]}")));
        CompiledGraph graph = graphWith(new ObjectMapper(), messages, conversations, model, budget);

        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("goal", "工作目标");
        initial.put("stage", "START");
        initial.put("draftRound", 0);
        initial.put("currentInstruction", "本轮指令");
        initial.put("conversationHistory", chatHistory(6, "状态轮%d", 150));
        graph.stream(initial, RunnableConfig.builder().threadId("ctx-llm1").build())
                .collectList().block(Duration.ofSeconds(20));

        assertThat(model.calls()).isEqualTo(2);
        String prompt = model.prompts().get(1);
        assertThat(prompt).contains("【会话摘要】");
        assertThat(prompt).contains("前两轮已完成");
        System.out.printf("测试证据：场景=LLM压缩放行，压缩调用=%d，业务输入含会话摘要=%s%n",
                model.calls() - 1, prompt.contains("前两轮已完成"));
    }

    // ---------- 基础设施 ----------

    private CompiledGraph graphWith(
            ObjectMapper objectMapper, KnowledgeTaskMessageMapper messages,
            KnowledgeTaskConversationMapper conversations, RecordingModel model, ContextBudget budget
    ) {
        try {
            KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(objectMapper,
                    new ContextAssemblyService(conversations, messages, budget, new ContextTokenEstimator(),
                            new ContextCompressionService(objectMapper, messages, new ContextTokenEstimator())));
            List<AgentSpec> specs = loadSpecs();
            factory.validate(specs, ALL_TOOLS);
            Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                    n -> n, KnowledgeCurationContextAssemblyIT::tool, (a, b) -> a, LinkedHashMap::new));
            StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
            return factory.build(new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                    Map.of("operatorId", "admin", "projectIdentifier", "atlas", "conversationId", 1L, "runId", 2L),
                    saver(), List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor())
                    .graph();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskMessageEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);
    }

    private PostgresSaver saver() {
        return PostgresSaver.builder().datasource(dataSource()).createOption(CreateOption.CREATE_NONE).build();
    }

    private PostgresSaver saverWithoutSchema() {
        return saver();
    }

    private javax.sql.DataSource dataSource() {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private KnowledgeCurationRunExecutor executor(RecordingModel model, PostgresSaver saver) {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.markKnowledgeRunning(any(), any())).thenReturn(1);
        when(runs.markKnowledgeRecovery(any(), any())).thenReturn(1);
        return executor(model, saver, ContextAssemblyFixtures.budget(), runs, mock(KnowledgeTaskMessageMapper.class));
    }

    private KnowledgeCurationRunExecutor executor(
            RecordingModel model, PostgresSaver saver, ContextBudget budget,
            AgentRunMapper runs, KnowledgeTaskMessageMapper messages
    ) {
        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationContextAssemblyIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        return new KnowledgeCurationRunExecutor(
                provider(model), properties(), resolver, saver, definitions, new ObjectMapper(),
                runs, mock(KnowledgeTaskConversationMapper.class), messages,
                mock(AgentEventService.class), mock(KnowledgeTaskEventService.class),
                mock(KnowledgeToolInvocationService.class), mock(KnowledgeTaskRunProjectionService.class),
                scheduler(), budget, Clock.systemUTC());
    }

    private BoundedAgentRunScheduler scheduler() {
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
        return scheduler;
    }

    private AgentProperties properties() {
        return new AgentProperties(
                true, true,
                new AgentProperties.Model("openai-compatible", "fake", "http://localhost:1", "key",
                        Duration.ofMillis(100), Duration.ofSeconds(2), 0),
                new AgentProperties.Policy("project-qa-v1"),
                new AgentProperties.Limits(20, 20, 64, 32, Duration.ofSeconds(120), 20, 2000, 24000, 8000, 200, 0.2),
                new AgentProperties.Executor(1, 2, 10, Duration.ofSeconds(5)));
    }

    private AgentRunEntity run(Long id, String threadId) {
        return AgentRunEntity.builder()
                .id(id).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status("ACCEPTED")
                .threadId(threadId).acceptedAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    private KnowledgeAgentDefinitionService.LoadedDefinition definition() {
        return new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "current-digest", "m", ALL_TOOLS));
    }

    private ObjectProvider<ChatModel> provider(ChatModel model) {
        return new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return model; }
            @Override public ChatModel getIfAvailable() { return model; }
            @Override public ChatModel getIfUnique() { return model; }
            @Override public ChatModel getObject() { return model; }
        };
    }

    private static List<AgentSpec> loadSpecs() {
        return SPEC_FILES.stream().map(n -> readSpec("agent-specs/knowledge-curation/" + n)).toList();
    }

    private static AgentSpec readSpec(String path) {
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

    private record EchoInput(String value) {
    }

    private static ChatResponse answer(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder().build());
    }

    private static ChatResponse toolCallAnswer(String toolName, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", toolName, arguments)))
                .build())), ChatResponseMetadata.builder().build());
    }

    private static List<Message> chatHistory(int rounds, String pattern, int fill) {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            history.add(new UserMessage(String.format(pattern + "用户" + "长".repeat(fill), i)));
            history.add(new AssistantMessage(String.format(pattern + "回复" + "长".repeat(fill), i)));
        }
        return history;
    }

    private static List<KnowledgeTaskMessageEntity> oldTurnRows(long runId, String targetSkill, int turns) {
        List<KnowledgeTaskMessageEntity> rows = new ArrayList<>();
        long id = 1000L;
        for (int i = 0; i < turns; i++) {
            KnowledgeTaskMessageEntity user = new KnowledgeTaskMessageEntity();
            user.setId(id++); user.setConversationId(1L); user.setRunId(runId + i);
            user.setRole("USER"); user.setSubjectName("me"); user.setContent("第" + i + "轮用户指令");
            rows.add(user);
            KnowledgeTaskMessageEntity finalReply = new KnowledgeTaskMessageEntity();
            finalReply.setId(id++); finalReply.setConversationId(1L); finalReply.setRunId(runId + i);
            finalReply.setRole("COORDINATOR_AGENT"); finalReply.setSubjectName(targetSkill);
            finalReply.setContent("第" + i + "轮最终回复");
            rows.add(finalReply);
        }
        return rows;
    }

    private static String retrievalJson() {
        return "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                + "\"facts\":[{\"statement\":\"新增背景\",\"support\":\"SUPPORTED\","
                + "\"sourceRefs\":[{\"type\":\"EVIDENCE\",\"id\":88}]}],"
                + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}";
    }

    /** 记录模型调用与按序 prompt 的脚本化 ChatModel。 */
    private static final class RecordingModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();

        private RecordingModel(List<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            return responses.get(Math.min(calls.getAndIncrement(), responses.size() - 1));
        }

        @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }

        int calls() {
            return calls.get();
        }

        List<String> prompts() {
            return List.copyOf(prompts);
        }
    }
}
