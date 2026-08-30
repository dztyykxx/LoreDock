package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.github.loredock.memory.mapper.UserMemoryMapper;
import io.github.loredock.memory.model.entity.UserMemoryEntity;
import io.github.loredock.memory.service.MemoryServiceImpl;
import io.github.loredock.memory.service.MemoryWriteJudger;
import io.github.loredock.memory.testsupport.MemoryTestFixtures;
import io.github.loredock.persistence.MybatisMapperFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
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
            "draft_create", "draft_read", "draft_update", "draft_rename", "draft_diff",
            "memory_search", "memory_read", "memory_write");

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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

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
     * 业务目的：run 在模型循环中途被取消后，必须在下一次模型调用前立即停止（不再跑完整轮），
     * 且不得产出最终回复消息或完成状态；防止取消后的"幽灵执行"继续烧 token 并让该 run 的
     * 全部工具调用因 status≠RUNNING 而报"知识 Tool 上下文与运行固定范围不一致"。
     * 取消点：首个模型调用返回后切 CANCELLED，第二次调用不得发生。
     */
    @Test
    void cancelDuringRunStopsBeforeNextModelCallAndNeverPublishesReply() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);

        ScriptedChatModel inner = new ScriptedChatModel(List.of(
                answer("{\"action\":\"FULL_CURATION\",\"summary\":\"开始整理\",\"expertCalls\":[]}"),
                answer("{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,\"facts\":[],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}")));
        java.util.concurrent.atomic.AtomicReference<String> status =
                new java.util.concurrent.atomic.AtomicReference<>("RUNNING");
        ChatModel flippingModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                ChatResponse response = inner.call(prompt);
                status.set("CANCELLED");
                return response;
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                ChatResponse response = inner.call(prompt);
                status.set("CANCELLED");
                return Flux.just(response);
            }
        };
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return flippingModel; }
            @Override public ChatModel getIfAvailable() { return flippingModel; }
            @Override public ChatModel getIfUnique() { return flippingModel; }
            @Override public ChatModel getObject() { return flippingModel; }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity();
        when(runs.selectById(run.getId())).thenAnswer(inv -> AgentRunEntity.builder()
                .id(run.getId()).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status(status.get())
                .threadId(run.getThreadId()).acceptedAt(run.getAcceptedAt()).updatedAt(run.getUpdatedAt()).build());
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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

        executor.start(run, "开始整理", new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // 取消发生在第一次模型调用之后：第二次调用不得发生，也不得写入最终回复与完成状态。
        assertThat(inner.calls()).isEqualTo(1);
        verify(messages, never()).insert(org.mockito.ArgumentMatchers.any(KnowledgeTaskMessageEntity.class));
        verify(runs, never()).completeKnowledge(any(), any(), anyInt(), anyInt(), anyInt(), anyLong(), any(), any(), any());
        System.out.println("测试证据：场景=中途取消中止执行，模型调用=1，最终回复=0，完成状态=0");
    }

    /**
     * 业务目的：取消发生在单个 Agent 节点的模型循环中途（检索 Agent 本轮内有工具调用、存在第二次模型调用）时，
     * 必须在下一次模型调用前立即中止（中断边界只在节点完成后才检查，节点内的后续循环是"幽灵执行"窗口）；
     * 防止取消后的旧 run 继续向模型发送请求并让该 run 的工具调用因 status≠RUNNING 全部报
     * "知识 Tool 上下文与运行固定范围不一致"。
     */
    @Test
    void cancelInsideAgentNodeStopsAtNextModelCall() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);

        // 检索 Agent 节点的第一轮模型调用返回工具调用（节点内会接着执行工具并第二次调用模型）；
        // 取消发生在该工具调用之后，第二次模型调用不得发生。
        ScriptedChatModel inner = new ScriptedChatModel(List.of(
                answer("{\"action\":\"FULL_CURATION\",\"summary\":\"开始整理\",\"expertCalls\":[]}"),
                answerToolCall("knowledge_grep", "{\"value\":\"检索\"}"),
                answer("{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,\"facts\":[],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}")));
        java.util.concurrent.atomic.AtomicReference<String> status =
                new java.util.concurrent.atomic.AtomicReference<>("RUNNING");
        ChatModel flippingModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                ChatResponse response = inner.call(prompt);
                if (inner.calls() >= 2) {
                    status.set("CANCELLED");
                }
                return response;
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                ChatResponse response = inner.call(prompt);
                if (inner.calls() >= 2) {
                    status.set("CANCELLED");
                }
                return Flux.just(response);
            }
        };
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return flippingModel; }
            @Override public ChatModel getIfAvailable() { return flippingModel; }
            @Override public ChatModel getIfUnique() { return flippingModel; }
            @Override public ChatModel getObject() { return flippingModel; }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity();
        when(runs.selectById(run.getId())).thenAnswer(inv -> AgentRunEntity.builder()
                .id(run.getId()).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status(status.get())
                .threadId(run.getThreadId()).acceptedAt(run.getAcceptedAt()).updatedAt(run.getUpdatedAt()).build());
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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

        executor.start(run, "开始整理", new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // 取消发生在检索节点第一轮模型调用（工具调用）之后：节点内第二次模型调用不得发生，
        // 也不得写入最终回复与完成状态。
        assertThat(inner.calls()).isEqualTo(2);
        verify(messages, never()).insert(org.mockito.ArgumentMatchers.any(KnowledgeTaskMessageEntity.class));
        verify(runs, never()).completeKnowledge(any(), any(), anyInt(), anyInt(), anyInt(), anyLong(), any(), any(), any());
        System.out.println("测试证据：场景=节点内取消立即中止，模型调用=2（第二次被中止），最终回复=0，完成状态=0");
    }

    /**
     * 业务目的：主 Agent 最终输出包含重复 JSON 键与 JSON 后的附加散文（真实模型缺陷）时，
     * 最终回复解析必须与路由条件边共用同一容错（JsonNode 层 last-wins + 首尾括号截取），
     * 不能出现"路由能过、最终回复解析失败"把整个 run 打成 AGENT_MODEL_RESPONSE_INVALID 的分叉。
     */
    @Test
    void duplicateKeyAndTrailingProseInFinalReplyStillCompletes() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"action\":\"CHAT\",\"summary\":\"你好，我在线。\",\"expertCalls\":[],"
                        + "\"action\":\"CHAT\"} Let me correct that - duplicate key")));
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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

        executor.start(run, "你好", new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // 重复键 + 尾随散文的最终输出仍应完成 run 并以 summary 作为最终回复。
        verify(runs).completeKnowledge(eq(run.getId()), eq("你好，我在线。"), any(int.class), any(int.class),
                any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));
        verify(messages).insert(org.mockito.ArgumentMatchers.<KnowledgeTaskMessageEntity>argThat(message ->
                message.getRole().equals("COORDINATOR_AGENT")
                        && message.getContent().equals("你好，我在线。")));
        System.out.println("测试证据：场景=最终回复重复键+尾随散文容错，解析=last-wins+括号截取，最终回复=你好，我在线。");
    }

    /**
     * 业务目的：run 被取消后，即使图执行仍在推进，也绝不允许把最终回复消息与 COMPLETED 状态落库
     * （取消只在图边界可见，本轮不再产出任何结果）。防止取消后的旧 run 把半截轮次的总结写进会话。
     */
    @Test
    void cancelDuringRunSuppressesFinalReplyAtBoundary() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel inner = new ScriptedChatModel(List.of(
                answer("{\"action\":\"CHAT\",\"summary\":\"你好，我在线，能看到上轮结论。\",\"expertCalls\":[]}")));
        java.util.concurrent.atomic.AtomicReference<String> status =
                new java.util.concurrent.atomic.AtomicReference<>("RUNNING");
        ChatModel flippingModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                ChatResponse response = inner.call(prompt);
                status.set("CANCELLED");
                return response;
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                ChatResponse response = inner.call(prompt);
                status.set("CANCELLED");
                return Flux.just(response);
            }
        };
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return flippingModel; }
            @Override public ChatModel getIfAvailable() { return flippingModel; }
            @Override public ChatModel getIfUnique() { return flippingModel; }
            @Override public ChatModel getObject() { return flippingModel; }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity();
        when(runs.selectById(run.getId())).thenAnswer(inv -> AgentRunEntity.builder()
                .id(run.getId()).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status(status.get())
                .threadId(run.getThreadId()).acceptedAt(run.getAcceptedAt()).updatedAt(run.getUpdatedAt()).build());
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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

        executor.start(run, "你好", new KnowledgeAgentDefinitionService.LoadedDefinition(
                new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                        "knowledge-curator", "s", "d", "m", ALL_TOOLS)));

        // 轮次已达成但 run 已取消：最终回复与完成状态均不得落库。
        assertThat(inner.calls()).isEqualTo(1);
        verify(messages, never()).insert(org.mockito.ArgumentMatchers.any(KnowledgeTaskMessageEntity.class));
        verify(runs, never()).completeKnowledge(any(), any(), anyInt(), anyInt(), anyInt(), anyLong(), any(), any(), any());
        System.out.println("测试证据：场景=最终轮次取消抑制回复，模型调用=1，最终回复=0，完成状态=0");
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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

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
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                noMemorySupply());

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

    /**
     * 业务目的：端到端验证「偏好沉淀 → 下一轮预载注入 → 偏好随起草指令传达」完整闭环——
     * run A 用户表达长期偏好，主 Agent 调 memory_write 由真实判断链写入一条 GLOBAL 记忆；
     * 同一会话下一轮 run B 的主 Agent 上下文出现【用户记忆】块（行尾编号与 DB 一致、run 内两
     * 次主 Agent 注入相同），起草指令携带该偏好、记忆标题/编号/正文不进入任何专家视图，
     * 且完整整理路径与公开阶段事件、等待人工发布的门禁行为保持不变。
     * 防止：记忆只写不注（沉淀后下一轮模型看不到自己的偏好），或记忆泄漏进专家视图被当成证据。
     */
    @Test
    void memoryWrittenInRunCarriesIntoNextRunCurationAsStablePrefix() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        javax.sql.DataSource dataSource = dataSource();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource)
                .createOption(CreateOption.CREATE_NONE).build();
        // 协调 Agent 的 persistCoordinatorProgress 用 MyBatis-Plus lambdaUpdate 触达会话表，需手动注册 TableInfo。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);

        // 来源外键种子：memory_write 落库 user_memory.source_run_id/source_conversation_id 有真实外键
        // （agent_run(2) + knowledge_task_conversation(100)），不能只依赖 Mockito 的 run mapper。
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String hex = "a".repeat(64);
        String digestB = "b".repeat(64);
        String digestC = "c".repeat(64);
        Instant base = Instant.parse("2026-08-30T08:00:00Z");
        String thread = "e2e-memory-thread-" + System.nanoTime();
        jdbc.update("""
                insert into knowledge_task_conversation
                    (id, operator_id, idempotency_key, request_hash, project_id, project_identifier,
                     trigger_type, trigger_reason, target_skill, goal, created_at, updated_at)
                values (?, 'admin', 'e2e-conv-100', ?, null, 'atlas', 'MANUAL', '用户记忆端到端测试', 'knowledge-curator',
                        '记录并整理项目背景', ?, ?)
                """, 100L, hex, base.atOffset(ZoneOffset.UTC), base.atOffset(ZoneOffset.UTC));
        jdbc.update("""
                insert into agent_run
                    (id, operator_id, idempotency_key, request_hash, task_type, question_hash,
                     question_length, project_id, project_identifier, branch_id, branch_name,
                     agent_name, model_name, config_summary, knowledge_task_conversation_id, thread_id,
                     skill_digest, agent_spec_digest, tool_names, status, accepted_at, updated_at)
                values (?, 'admin', 'e2e-run-2', ?, 'knowledge_curation', ?, 20, null, 'atlas', null, 'main',
                        'knowledge-curator', 'fake', 'it-config', 100, ?, ?, ?, 'memory_search,memory_read,memory_write',
                        'RUNNING', ?, ?)
                """, 2L, hex, hex, thread, digestB, digestC, base.atOffset(ZoneOffset.UTC), base.atOffset(ZoneOffset.UTC));

        // 真实记忆服务 + 真实 MemoryTools：memory_write 走完整判断链（脚本化判断模型返回 CREATED）。
        UserMemoryMapper memoryMapper = MybatisMapperFactory.create(dataSource, UserMemoryMapper.class);
        java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);
        MemoryServiceImpl memoryService = new MemoryServiceImpl(
                memoryMapper, MemoryTestFixtures.projectService(),
                new MemoryWriteJudger(MemoryTestFixtures.single(new FixedChatModel("""
                        [{"candidateIndex":0,"verdict":"CREATED","conflictsWith":[],"summary":"正文使用三级标题"}]
                        """)), new ObjectMapper()),
                MemoryTestFixtures.properties(), clock);
        MemoryPreloadSupply supply = new MemoryPreloadSupply(memoryService);

        ScriptedChatModel modelA = new ScriptedChatModel(List.of(
                answerToolCall("memory_write", "{\"candidates\":[{\"title\":\"正文格式偏好\","
                        + "\"content\":\"正文使用三级标题\",\"category\":\"FORMAT\",\"summary\":null}]}"),
                answer("{\"action\":\"TURN_DONE\",\"summary\":\"已记住你的偏好：文档正文使用三级标题\",\"expertCalls\":[]}")));
        // run B 完整整理脚本顺序：主 Agent FULL_CURATION → retriever → coordinator DECIDE（起草指令携带偏好）
        // → drafter → reviewer PASS → coordinator FINISH/END → 主 Agent TURN_DONE。
        ScriptedChatModel modelB = new ScriptedChatModel(List.of(
                answer("{\"action\":\"FULL_CURATION\",\"summary\":\"开始整理\",\"expertCalls\":[]}"),
                answer("{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                        + "\"facts\":[{\"statement\":\"新增背景\",\"support\":\"SUPPORTED\","
                        + "\"sourceRefs\":[{\"type\":\"EVIDENCE\",\"id\":88}]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}"),
                answer("{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                        + "\"draftInstruction\":\"正文按用户偏好使用三级标题\",\"question\":null,\"summary\":\"决定起草\"}"),
                answer("{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                        + "\"question\":null,\"summary\":\"已写入\"}"),
                answer("{\"verdict\":\"PASS\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[],\"question\":null,\"summary\":\"审查通过\"}"),
                answer("{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}"),
                answer("{\"action\":\"TURN_DONE\",\"summary\":\"已完成整理\",\"expertCalls\":[]}")));
        java.util.concurrent.atomic.AtomicReference<ChatModel> activeModel =
                new java.util.concurrent.atomic.AtomicReference<>();
        ObjectProvider<ChatModel> modelProvider = new ObjectProvider<ChatModel>() {
            @Override public ChatModel getObject(Object... args) { return activeModel.get(); }
            @Override public ChatModel getIfAvailable() { return activeModel.get(); }
            @Override public ChatModel getIfUnique() { return activeModel.get(); }
            @Override public ChatModel getObject() { return activeModel.get(); }
        };

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity runA = runEntity(2L, thread);
        AgentRunEntity runB = runEntity(3L, thread);
        when(runs.selectById(2L)).thenReturn(runA);
        when(runs.selectById(3L)).thenReturn(runB);
        when(runs.markKnowledgeRunning(anyLong(), any())).thenReturn(1);

        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        KnowledgeTaskEventService taskEvents = mock(KnowledgeTaskEventService.class);

        // 记忆三工具替换 echo 占位，其余工具仍为占位实现；工具名与占位一致，仅实现生效。
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationRunExecutorDriveIT::tool, (a, b) -> a, LinkedHashMap::new));
        MemoryTools memoryTools = new MemoryTools(memoryService, runs);
        for (ToolCallback callback : MethodToolCallbackProvider.builder().toolObjects(memoryTools).build()
                .getToolCallbacks()) {
            callbacks.put(callback.getToolDefinition().name(), callback);
        }
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));

        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(anyLong(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = new KnowledgeCurationRunExecutor(
                modelProvider, properties(), resolver, saver, definitions, new ObjectMapper(),
                runs, mock(KnowledgeTaskConversationMapper.class), messages, events, taskEvents,
                mock(KnowledgeToolInvocationService.class), mock(KnowledgeTaskRunProjectionService.class),
                scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                supply);
        KnowledgeAgentDefinitionService.LoadedDefinition definition =
                new KnowledgeAgentDefinitionService.LoadedDefinition(
                        new io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition(
                                "knowledge-curator", "s", "d", "m", ALL_TOOLS));

        // —— run A：沉淀偏好（此时库里还没有任何记忆，主 Agent 上下文不得出现【用户记忆】块。
        // 注意：主 Agent spec 本身含「记忆边界」说明文本，须用块独有标记（编号行/核对指引）断言块不存在。
        activeModel.set(modelA);
        executor.start(runA, "记住：我偏好正文使用三级标题", definition);
        for (String prompt : modelA.prompts()) {
            assertThat(prompt)
                    .doesNotContain("（编号 ", "如需核对全文请用 memory_read 并传行尾编号")
                    .doesNotContain("[FORMAT/GLOBAL]");
        }
        verify(runs).completeKnowledge(eq(2L), eq("已记住你的偏好：文档正文使用三级标题"), any(int.class),
                any(int.class), any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));

        // —— DB 断言：记忆由真实服务写入（来源、范围、分类、摘要与判断结论一致）。
        UserMemoryEntity persisted = memoryMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserMemoryEntity>lambdaQuery()
                        .eq(UserMemoryEntity::getSourceRunId, 2L)).get(0);
        assertThat(persisted.getScopeType()).isEqualTo("GLOBAL");
        assertThat(persisted.getCategory()).isEqualTo("FORMAT");
        assertThat(persisted.getTitle()).isEqualTo("正文格式偏好");
        assertThat(persisted.getSummary()).isEqualTo("正文使用三级标题");
        assertThat(persisted.getContent()).isEqualTo("正文使用三级标题");
        assertThat(persisted.getStatus()).isEqualTo("ACTIVE");
        assertThat(persisted.getSourceRunId()).isEqualTo(2L);
        assertThat(persisted.getSourceConversationId()).isEqualTo(100L);
        assertThat(persisted.getSourceType()).isEqualTo("KNOWLEDGE_CURATION");
        assertThat(persisted.getCreatedBy()).isEqualTo("admin");

        // —— run B：同一会话下一轮，主 Agent 两次进入都注入同一编号的【用户记忆】块（run 固定快照）。
        activeModel.set(modelB);
        executor.start(runB, "正文使用三级标题", definition);
        List<String> prompts = modelB.prompts();
        assertThat(prompts).hasSize(7);
        String block = "- [FORMAT/GLOBAL] 正文格式偏好：正文使用三级标题（编号 " + persisted.getId() + "）";
        for (int index : new int[]{0, 6}) {
            assertThat(prompts.get(index)).contains("【用户记忆】").contains(block)
                    .contains("如需核对全文请用 memory_read 并传行尾编号");
        }
        // 专家视图（retriever/coordinator DECIDE/drafter/reviewer/coordinator FINISH）不得出现记忆块痕迹。
        for (int index : new int[]{1, 2, 3, 4, 5}) {
            assertThat(prompts.get(index))
                    .doesNotContain("【用户记忆】", "正文格式偏好", "（编号 " + persisted.getId() + "）");
        }
        // 起草指令携带偏好：drafter（第 4 次调用）拿到协调 Agent 下达的偏好指令。
        assertThat(prompts.get(3)).contains("正文按用户偏好使用三级标题");
        // 完整路径照常收口 + 公开阶段事件不因记忆注入变化。
        verify(runs).completeKnowledge(eq(3L), eq("已完成整理"), any(int.class),
                any(int.class), any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));
        verify(events, atLeast(1)).append(eq(3L), eq(io.github.loredock.agent.model.enums.AgentEventType.AGENT_STAGE),
                eq(io.github.loredock.agent.api.AgentEvent.SubjectType.AGENT),
                argThat(payload -> "main_agent".equals(payload.name()) && "MAIN".equals(payload.phase())),
                any(Instant.class));
        System.out.printf("测试证据：场景=记忆端到端，写入 id=%d(%s/%s)，runB主Agent两次注入=%s，专家视图无记忆块，发布门禁不变%n",
                persisted.getId(), persisted.getScopeType(), persisted.getCategory(), block.replace("\n", ""));
    }

    private AgentRunEntity runEntity() {
        return runEntity(1L, "executor-thread-" + System.nanoTime());
    }

    private AgentRunEntity runEntity(long id, String threadId) {
        return AgentRunEntity.builder()
                .id(id).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status("RUNNING")
                .threadId(threadId)
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

    /** 记忆未装配的测试场景：供应返回空快照 → 组装不注入【用户记忆】块（Mockito 默认空列表）。 */
    private MemoryPreloadSupply noMemorySupply() {
        return new MemoryPreloadSupply(mock(io.github.loredock.memory.api.MemoryService.class));
    }

    private static ChatResponse answer(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder().build());
    }

    /** 构造带单个工具调用的模型响应：模拟 Agent 循环内"先调用工具再二次调用模型"的中间轮次。 */
    private static ChatResponse answerToolCall(String toolName, String argumentsJson) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_a1", "function", toolName, argumentsJson)))
                .build())), ChatResponseMetadata.builder().build());
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

    /** 固定结论的 ChatModel：驱动记忆写入判断链（返回预置 verdict JSON），模拟判断器模型。 */
    private static final class FixedChatModel implements ChatModel {
        private final String reply;

        private FixedChatModel(String reply) { this.reply = reply; }

        @Override public ChatResponse call(Prompt prompt) { return answer(reply); }

        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
    }
}
