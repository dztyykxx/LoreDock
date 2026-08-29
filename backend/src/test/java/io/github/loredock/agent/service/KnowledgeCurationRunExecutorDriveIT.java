package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import reactor.core.publisher.Flux;

/** 验证 Executor 通过真实父 Graph 驱动 CHAT 短路并落库最终回复（步骤 2 核心路径）。 */
@Testcontainers
class KnowledgeCurationRunExecutorDriveIT {

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
                answer("{\"stage\":\"START\",\"action\":\"CHAT\",\"reason\":\"问候\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"你好，我在线。\"}")));
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

        // 完成 run：最终回复取 coordinator 的 CHAT summary，且不以全限定名重复出现。
        verify(runs).completeKnowledge(eq(run.getId()), eq("你好，我在线。"), any(int.class), any(int.class),
                any(int.class), any(long.class), any(Instant.class));
        verify(messages).insert(org.mockito.ArgumentMatchers.<KnowledgeTaskMessageEntity>argThat(message ->
                message.getRole().equals("COORDINATOR_AGENT")
                        && message.getContent().equals("你好，我在线。")));
        // §10.6：调度 Agent 完成必须提交一条 AGENT_STAGE 公开事件，用稳定名称 coordinator 与阶段 START。
        verify(events).append(eq(run.getId()), eq(io.github.loredock.agent.model.enums.AgentEventType.AGENT_STAGE),
                eq(io.github.loredock.agent.api.AgentEvent.SubjectType.AGENT),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "coordinator".equals(payload.name()) && "START".equals(payload.phase())
                                && "COMPLETED".equals(payload.status())),
                any(Instant.class));
        verify(taskEvents).append(eq(run.getKnowledgeTaskConversationId()), eq(run.getId()),
                eq("AGENT_STAGE_UPDATED"), eq(run.getId()), any(Instant.class));
        assertThat(model.calls()).isEqualTo(1);
        System.out.printf("测试证据：场景=Executor闲聊短路，模型调用=%d，最终回复=%s，阶段事件=AGENT_STAGE%n",
                model.calls(), "你好，我在线。");
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
    }
}
