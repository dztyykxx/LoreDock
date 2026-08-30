package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

import org.mockito.ArgumentCaptor;

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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
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
import reactor.core.publisher.Flux;

/** 验证 Executor 通过真实父 Graph 驱动 CHAT 短路并落库最终回复（步骤 2 核心路径）。 */
@Testcontainers
class KnowledgeCurationRunExecutorDriveIT {

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
            .withDatabaseName("loredock_executor").withUsername("loredock").withPassword("loredock_test");

    private static String schema = "executor_" + System.nanoTime();

    /**
     * 业务目的：普通闲聊只经调度 Agent 短路，Executor 必须把 coordinator 的 summary 作为最终回复写入
     * 任务对话并完成 run，且不产生检索、草稿、审查专家调用；防止闲聊被误当知识整理走完整图。
     */
    @Test
    void executorDrivesChatShortCircuitAndPersistsFinalReply() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"action\":\"CHAT\",\"summary\":\"你好，我在线，能看到上轮结论。\",\"expertCalls\":[]}")));
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return model; }
            @Override public ChatModel getIfAvailable() { return model; }
            @Override public ChatModel getIfUnique() { return model; }
            @Override public ChatModel getObject() { return model; }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity();
        when(runs.selectById(run.getId())).thenReturn(run);
        when(runs.markKnowledgeRunning(eq(run.getId()), any())).thenReturn(1);

        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        KnowledgeTaskEventService taskEvents = mock(KnowledgeTaskEventService.class);

        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationRunExecutorDriveIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));

        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(eq(run.getId()), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = new KnowledgeCurationRunExecutor(
                modelProvider, properties(), resolver, saver, definitions, new ObjectMapper(),
                runs, mock(KnowledgeTaskConversationMapper.class), messages, events, taskEvents,
                mock(KnowledgeToolInvocationService.class), mock(KnowledgeTaskRunProjectionService.class),
                scheduler, Clock.systemUTC());

        executor.start(run, "你好", new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // 完成 run：最终回复取 coordinator 的 CHAT summary；闲聊响应不含 token 用量，故 run 级 token 记为 null。
        verify(runs).completeKnowledge(eq(run.getId()), eq("你好，我在线，能看到上轮结论。"), any(int.class), any(int.class),
                any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));
        verify(messages).insert(org.mockito.ArgumentMatchers.<KnowledgeTaskMessageEntity>argThat(message ->
                message.getRole().equals("COORDINATOR_AGENT")
                        && message.getContent().equals("你好，我在线，能看到上轮结论。")));
        // §10.6：主 Agent 完成必须提交一条 AGENT_STAGE 公开事件，用稳定名称 main_agent 与阶段 MAIN。
        verify(events).append(eq(run.getId()), eq(io.github.loredock.agent.model.enums.AgentEventType.AGENT_STAGE),
                eq(io.github.loredock.agent.api.AgentEvent.SubjectType.AGENT),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "main_agent".equals(payload.name()) && "MAIN".equals(payload.phase())
                                && "COMPLETED".equals(payload.status())),
                any(Instant.class));
        verify(taskEvents).append(eq(run.getKnowledgeTaskConversationId()), eq(run.getId()),
                eq("AGENT_STAGE_UPDATED"), eq(run.getId()), any(Instant.class));
        assertThat(model.calls()).isEqualTo(1);
        System.out.printf("测试证据：场景=Executor闲聊短路，模型调用=%d，最终回复=%s，阶段事件=AGENT_STAGE%n",
                model.calls(), "你好，我在线，能看到上轮结论。");
    }

    /**
     * 业务目的：知识整理完整路径必须把每次模型调用的 token 用量落库到 run 级（agent_run.input_tokens/output_tokens），
     * 并在每个 Agent 的 AGENT_STAGE 公开事件上展示其累计 token；防止“模型跑了但连 token 是否花费、哪个 Agent 最贵
     * 都无法观察”。使用带 usage 的脚本化模型灌注固定值，区分 run 级总量与逐 Agent 归属。
     */
    @Test
    void executorPersistsRunLevelAndPerAgentTokens() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // 完整路径的协调 Agent 会在 persistCoordinatorProgress 里用 MyBatis-Plus lambdaUpdate 触达知识会话表；
        // 单测构建的执行器未走 Spring 初始化，需手动注册该实体的 TableInfo，否则构建 lambda wrapper 抛 MybatisPlusException。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);

        // 顺序：主 Agent FULL_CURATION, retriever, coordinator DECIDE→DRAFT, drafter, reviewer,
        // coordinator FINISH→END, 主 Agent TURN_DONE 汇总。
        // 每次调用灌注独立的 prompt/completion token，便于核对逐 Agent 归属与 run 级加和。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"action\":\"FULL_CURATION\",\"summary\":\"开始整理\",\"expertCalls\":[]}", 100, 10),
                answer("{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                        + "\"facts\":[{\"statement\":\"新增背景\",\"support\":\"SUPPORTED\","
                        + "\"sourceRefs\":[{\"type\":\"EVIDENCE\",\"id\":88}]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}", 200, 20),
                answer("{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                        + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"决定起草\"}", 300, 30),
                answer("{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                        + "\"question\":null,\"summary\":\"已写入\"}", 400, 40),
                answer("{\"verdict\":\"PASS\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[],\"question\":null,\"summary\":\"审查通过\"}", 500, 50),
                answer("{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}", 600, 60),
                answer("{\"action\":\"TURN_DONE\",\"summary\":\"已完成整理\",\"expertCalls\":[]}", 700, 70)));
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return model; }
            @Override public ChatModel getIfAvailable() { return model; }
            @Override public ChatModel getIfUnique() { return model; }
            @Override public ChatModel getObject() { return model; }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity();
        when(runs.selectById(run.getId())).thenReturn(run);
        when(runs.markKnowledgeRunning(eq(run.getId()), any())).thenReturn(1);

        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        KnowledgeTaskEventService taskEvents = mock(KnowledgeTaskEventService.class);

        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationRunExecutorDriveIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));

        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(eq(run.getId()), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = new KnowledgeCurationRunExecutor(
                modelProvider, properties(), resolver, saver, definitions, new ObjectMapper(),
                runs, mock(KnowledgeTaskConversationMapper.class), messages, events, taskEvents,
                mock(KnowledgeToolInvocationService.class), mock(KnowledgeTaskRunProjectionService.class),
                scheduler, Clock.systemUTC());

        executor.start(run, "请整理背景", new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // run 级：7 次调用输入 token = 100+200+300+400+500+600+700 = 2800，输出 = 10+20+30+40+50+60+70 = 280。
        ArgumentCaptor<Long> inCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> outCaptor = ArgumentCaptor.forClass(Long.class);
        verify(runs).completeKnowledge(eq(run.getId()), eq("已完成整理"), any(int.class), any(int.class),
                any(int.class), any(long.class), inCaptor.capture(), outCaptor.capture(), any(Instant.class));
        Long actualIn = inCaptor.getValue();
        Long actualOut = outCaptor.getValue();
        System.out.println("测试证据[token调试]: 实际 run 级 input=" + actualIn + " output=" + actualOut);
        assertThat(actualIn).isEqualTo(2800L);
        assertThat(actualOut).isEqualTo(280L);
        // 逐 Agent：captor 收集所有 AGENT_STAGE 事件，断言各阶段“自身增量”token 归属正确。
        ArgumentCaptor<io.github.loredock.agent.api.AgentEvent.Payload> captor =
                ArgumentCaptor.forClass(io.github.loredock.agent.api.AgentEvent.Payload.class);
        verify(events, atLeast(1)).append(eq(run.getId()),
                eq(io.github.loredock.agent.model.enums.AgentEventType.AGENT_STAGE),
                eq(io.github.loredock.agent.api.AgentEvent.SubjectType.AGENT),
                captor.capture(), any(Instant.class));
        var byAgent = new LinkedHashMap<String, io.github.loredock.agent.api.AgentEvent.Payload>();
        for (var payload : captor.getAllValues()) {
            if (payload.name() != null) {
                byAgent.put(payload.name(), payload); // 每 Agent 取最后一次(该阶段自身增量)事件
            }
        }
        // 主 Agent 2 次（FULL_CURATION/汇总）各报增量：最后事件 700/70；调度 2 次（DECIDE/FINISH）最后 600/60。
        // 按事件相加等于 run 级总量（2800/280），防止累计值重复计入造成前端总和对不上（联调 bug）。
        int coordinatorEvents = captor.getAllValues().stream()
                .filter(p -> "coordinator".equals(p.name())).toList().size();
        assertThat(coordinatorEvents).isEqualTo(2);
        assertThat(captor.getAllValues().stream()
                .filter(p -> "main_agent".equals(p.name())).count()).isEqualTo(2);
        assertThat(byAgent.get("main_agent").promptTokens()).isEqualTo(700);
        assertThat(byAgent.get("main_agent").completionTokens()).isEqualTo(70);
        assertThat(byAgent.get("coordinator").promptTokens()).isEqualTo(600);
        assertThat(byAgent.get("coordinator").completionTokens()).isEqualTo(60);
        // 单次 Agent 归属准确：retriever=200/20、drafter=400/40、reviewer=500/50。
        assertThat(byAgent.get("retriever").promptTokens()).isEqualTo(200);
        assertThat(byAgent.get("retriever").completionTokens()).isEqualTo(20);
        assertThat(byAgent.get("drafter").promptTokens()).isEqualTo(400);
        assertThat(byAgent.get("drafter").completionTokens()).isEqualTo(40);
        assertThat(byAgent.get("reviewer").promptTokens()).isEqualTo(500);
        assertThat(byAgent.get("reviewer").completionTokens()).isEqualTo(50);
        System.out.printf("测试证据：场景=完整路径token统计，run输入=%d输出=%d，main事件=%d，coordinator事件=%d(增量%d/%d)，retriever=%d/%d，drafter=%d/%d，reviewer=%d/%d%n",
                2800, 280, 2, coordinatorEvents, byAgent.get("coordinator").promptTokens(), byAgent.get("coordinator").completionTokens(),
                byAgent.get("retriever").promptTokens(), byAgent.get("retriever").completionTokens(),
                byAgent.get("drafter").promptTokens(), byAgent.get("drafter").completionTokens(),
                byAgent.get("reviewer").promptTokens(), byAgent.get("reviewer").completionTokens());
    }

    /**
     * 业务目的：暂停后恢复（resume，同一 run）时，管理员追加指导必须作为本轮用户消息进入协调 Agent 输入；
     * 否则协调 Agent 只看到最初 goal、看不到后续指令，会把"继续聊聊"当整理任务盲跑完整流程（联调缺陷）。
     */
    @Test
    void resumeInjectsGuidanceIntoCoordinatorPrompt() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"action\":\"CHAT\",\"summary\":\"你好，我在线，能看到上轮结论。\",\"expertCalls\":[]}")));
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return model; }
            @Override public ChatModel getIfAvailable() { return model; }
            @Override public ChatModel getIfUnique() { return model; }
            @Override public ChatModel getObject() { return model; }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity();
        when(runs.selectById(run.getId())).thenReturn(run);

        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationRunExecutorDriveIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));

        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(eq(run.getId()), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = new KnowledgeCurationRunExecutor(
                modelProvider, properties(), resolver, saver, definitions, new ObjectMapper(),
                runs, mock(KnowledgeTaskConversationMapper.class), mock(KnowledgeTaskMessageMapper.class),
                mock(AgentEventService.class), mock(KnowledgeTaskEventService.class),
                mock(KnowledgeToolInvocationService.class), mock(KnowledgeTaskRunProjectionService.class),
                scheduler, Clock.systemUTC());

        executor.resume(run, "将勾选草稿合并为一份稳定的业务知识", "你能看到上轮对话的哪些信息",
                new KnowledgeAgentDefinitionService.LoadedDefinition(
                        new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                                "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // 协调 Agent 的模型输入必须包含管理员追加指导，否则恢复即盲跑。
        assertThat(model.prompts()).isNotEmpty();
        assertThat(model.prompts().get(0)).contains("管理员追加指导：你能看到上轮对话的哪些信息");
        verify(runs).completeKnowledge(eq(run.getId()), eq("你好，我在线，能看到上轮结论。"), any(int.class),
                any(int.class), any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));
        System.out.printf("测试证据：场景=暂停恢复注入指导，prompt含指导=%s，最终回复=%s%n",
                model.prompts().get(0).contains("管理员追加指导：你能看到上轮对话的哪些信息"),
                "你好，我在线，能看到上轮结论。");
    }

    private AgentRunEntity runEntity() {
        return AgentRunEntity.builder()
                .id(1L).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status("RUNNING")
                .threadId("executor-thread-" + System.nanoTime())
                .acceptedAt(Instant.now()).updatedAt(Instant.now()).build();
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

    private javax.sql.DataSource dataSource() {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private List<AgentSpec> loadSpecs() {
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
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder().build());
    }

    /** 构造带 token 用量的流式响应：注入固定 prompt/completion token，用于验证执行器 token 采集与归属。 */
    private static ChatResponse answer(String json, int promptTokens, int completionTokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens)).build());
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();

        private ScriptedChatModel(List<ChatResponse> responses) { this.responses = responses; }

        @Override public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            return responses.get(Math.min(calls.getAndIncrement(), responses.size() - 1));
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }

        int calls() { return calls.get(); }

        List<String> prompts() { return List.copyOf(prompts); }
    }
}
