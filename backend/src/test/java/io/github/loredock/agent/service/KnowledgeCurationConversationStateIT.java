package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentEvent.SubjectType;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.enums.AgentEventType;
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

/**
 * 阶段 1 集成测试：会话级 threadId 与 WAIT_INPUT 轮次边界的真实 Graph 验证。
 *
 * <p>与 {@link KnowledgeCurationRunExecutorDriveIT} 一样使用真实 Flyway + PostgresSaver + 编译 Graph，
 * 只对 run 持久化/事件做 Mock，保证被验证的是 Graph 与 Executor 的恢复语义而不是测试桩。</p>
 */
@Testcontainers
class KnowledgeCurationConversationStateIT {

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
            .withDatabaseName("loredock_conversation_state").withUsername("loredock").withPassword("loredock_test");

    private static String schema = "conversation_state_" + System.nanoTime();

    /**
     * 业务目的：同一会话的下一轮必须复用同一 threadId、从 WAIT_INPUT 继续且不重放上一轮节点。
     * 防止回到“每轮新开 Graph、上下文丢失”的旧行为——上一轮模型调用被重复执行或本轮从 START 重跑。
     */
    @Test
    void continuedRoundReusesSessionThreadAndDoesNotReplayFirstRound() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        String sessionThread = "knowledge-task-conversation-100";
        // 第一轮 CHAT，第二轮 CHAT：如果第二轮从入口重跑或重放第一轮，模型调用次数会超过 2。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("第一轮已完成结论。\n{\"action\":\"CHAT\",\"expertCalls\":[]}"),
                answer("第二轮回复。\n{\"action\":\"CHAT\",\"expertCalls\":[]}")));
        ObjectProvider<ChatModel> modelProvider = provider(model);

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        KnowledgeTaskEventService taskEvents = mock(KnowledgeTaskEventService.class);
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = executor(modelProvider, saver, definitions, runs,
                messages, events, taskEvents, scheduler, mock(KnowledgeTaskRunProjectionService.class));

        AgentRunEntity first = runEntity(1L, "ACCEPTED", sessionThread);
        AgentRunEntity second = runEntity(2L, "ACCEPTED", sessionThread);
        when(runs.markKnowledgeRunning(eq(1L), any())).thenReturn(1);
        when(runs.markKnowledgeRunning(eq(2L), any())).thenReturn(1);

        executor.start(first, "第一轮目标", definition());
        executor.start(second, "第二轮目标", definition());

        assertThat(first.getThreadId()).isEqualTo(second.getThreadId());
        assertThat(model.calls()).isEqualTo(2);
        // 第二轮模型输入包含本轮指令；第一轮不被重放（无第三、第四次调用）。
        assertThat(model.prompts().get(1)).contains("第二轮目标");
        // 阶段4角色化历史（部分交付）：第二轮应看到上一轮最终回复的正文（角色化 AssistantMessage 已注入）；
        // 旧轮原始 JSON 清理依赖 messages 键策略改造（REPLACE 与框架自动追加行为的专项验证），留作后续批次。
        assertThat(model.prompts().get(1)).contains("第一轮已完成结论。");
        verify(runs).completeKnowledge(eq(1L), eq("第一轮已完成结论。"), any(int.class), any(int.class), any(int.class),
                any(long.class), isNull(), isNull(), any(Instant.class));
        verify(runs).completeKnowledge(eq(2L), eq("第二轮回复。"), any(int.class), any(int.class), any(int.class),
                any(long.class), isNull(), isNull(), any(Instant.class));
        System.out.printf("测试证据：场景=会话级续聊，threadId=%s，两轮模型调用=%d，第二轮prompt含本轮指令=%s%n",
                sessionThread, model.calls(), model.prompts().get(1).contains("第二轮目标"));
    }

    /**
     * 业务目的：暂停发生在节点边界后，恢复必须从该 Checkpoint 的下一节点继续，入口节点不重跑。
     * 防止“恢复时从 START/入口重跑”（旧问题：协调 Agent 用检索者的响应再次输出、路由错误）。
     */
    @Test
    void resumeAfterPauseContinuesFromCheckpointNextNodeWithoutEntryReplay() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // 恢复后完整跑完一轮：main FULL_CURATION → retriever → coordinator DECIDE/DRAFT → drafter → reviewer
        // → coordinator FINISH → main TURN_DONE 汇总（阶段 2 五 Agent 入口）。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("开始整理\n{\"action\":\"FULL_CURATION\",\"expertCalls\":[]}"),
                answer("{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                        + "\"facts\":[{\"statement\":\"新增背景\",\"support\":\"SUPPORTED\","
                        + "\"sourceRefs\":[{\"type\":\"EVIDENCE\",\"id\":88}]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"检索到事实\"}"),
                answer("{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"有支持事实\","
                        + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"决定起草\"}"),
                answer("{\"status\":\"WRITTEN\",\"drafts\":[{\"draftId\":19,\"revision\":3,\"operation\":\"ADD\"}],"
                        + "\"question\":null,\"summary\":\"已写入\"}"),
                answer("{\"verdict\":\"PASS\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[],\"question\":null,\"summary\":\"审查通过\"}"),
                answer("{\"stage\":\"FINISH\",\"action\":\"END\",\"reason\":\"完成\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"已完成整理\"}"),
                answer("已完成整理\n{\"action\":\"TURN_DONE\",\"expertCalls\":[]}")));
        ObjectProvider<ChatModel> modelProvider = provider(model);

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        KnowledgeTaskEventService taskEvents = mock(KnowledgeTaskEventService.class);
        KnowledgeTaskRunProjectionService projection = mock(KnowledgeTaskRunProjectionService.class);
        when(projection.markWaitingAfterInterrupt(eq(3L))).thenReturn(true);
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = executor(modelProvider, saver, definitions, runs,
                messages, events, taskEvents, scheduler, projection);

        String sessionThread = "knowledge-task-conversation-300";
        AgentRunEntity run = runEntity(3L, "PAUSE_REQUESTED", sessionThread);
        run.setAgentName("knowledge-curator");
        when(runs.selectById(3L)).thenReturn(run);
        when(runs.markKnowledgeRunning(eq(3L), any())).thenReturn(1);

        // 第一阶段：协调 Agent 输出 RETRIEVE 后在边界暂停，未进入检索节点。
        // coordinator 的 RETRIEVE 分支会走 persistCoordinatorProgress 的 MyBatis-Plus lambdaUpdate 触达知识会话表；
        // 单测构建的执行器未走 Spring 初始化，需手动注册该实体的 TableInfo，否则构建 lambda wrapper 抛 MybatisPlusException。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeTaskConversationEntity.class);
        executor.start(run, "将勾选草稿合并为知识", definition());
        assertThat(model.calls()).isEqualTo(1);
        // drive 暂停与执行器 catch 退出都会调用投影（后者依赖真实实现幂等返回 false），至少一次即完成暂停投影。
        verify(projection, atLeastOnce()).markWaitingAfterInterrupt(eq(3L));

        // 恢复阶段：从检索节点继续；主 Agent 的 FULL_CURATION 不再重跑（恢复段应为 6 次调用而非 7 次）。
        run.setStatus("RUNNING");
        executor.resume(run, "将勾选草稿合并为知识", "优先写入背景段落", definition());
        assertThat(model.calls()).isEqualTo(7);
        assertThat(model.prompts().get(1)).contains("管理员追加指导：优先写入背景段落");
        verify(runs, atLeastOnce()).completeKnowledge(eq(3L), eq("已完成整理"), any(int.class), any(int.class),
                any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));
        System.out.printf("测试证据：场景=暂停恢复不重跑入口，暂停时调用=%d，恢复后调用=%d，恢复首prompt是检索节点=%s%n",
                1, model.calls(), model.prompts().get(1).contains("管理员追加指导：优先写入背景段落"));
    }

    /**
     * 业务目的：run 记录的定义摘要与当前定义不一致时必须停止，不解析旧 Checkpoint、不启动模型。
     * 防止用新图/新提示词直接解读旧状态导致状态错读或业务重复写入。
     */
    @Test
    void definitionMismatchStopsRunWithoutModelCalls() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("{\"stage\":\"START\",\"action\":\"CHAT\",\"reason\":\"问候\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"不应出现\"}")));
        ObjectProvider<ChatModel> modelProvider = provider(model);

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));

        AgentRunMapper runs = mock(AgentRunMapper.class);
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        AgentEventService events = mock(AgentEventService.class);
        KnowledgeTaskEventService taskEvents = mock(KnowledgeTaskEventService.class);
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });

        KnowledgeCurationRunExecutor executor = executor(modelProvider, saver, definitions, runs,
                messages, events, taskEvents, scheduler, mock(KnowledgeTaskRunProjectionService.class));

        AgentRunEntity run = runEntity(4L, "ACCEPTED", "knowledge-task-conversation-400");
        // 持久化摘要与当前定义摘要不一致（当前定义为 "current-digest"）。
        run.setAgentSpecDigest("old-digest");
        run.setConfigSummary(KnowledgeCurationGraphFactory.GRAPH_DEF_VERSION);
        run.setAgentName("knowledge-curator");
        when(runs.markKnowledgeRunning(eq(4L), any())).thenReturn(1);

        executor.start(run, "任意目标", definition());

        assertThat(model.calls()).isEqualTo(0);
        verify(runs).failKnowledge(eq(4L), eq("AGENT_DEFINITION_MISMATCH"), any(int.class), any(int.class),
                any(int.class), any(long.class), any(Instant.class));
        verify(events).append(eq(4L), eq(AgentEventType.RUN_FAILED), eq(SubjectType.AGENT),
                org.mockito.ArgumentMatchers.argThat(payload -> payload.errorCode() != null
                        && "AGENT_DEFINITION_MISMATCH".equals(payload.errorCode().name())),
                any(Instant.class));
        System.out.printf("测试证据：场景=定义不一致停止，模型调用=%d，错误码=AGENT_DEFINITION_MISMATCH%n",
                model.calls());
    }

    /**
     * 业务目的：结构化结果首次无效时，同一 run 的同一 Agent 收到具体校验错误后重生成并正常完成，
     * run 不失败、不重新创建。防止“首次解析失败即终止整轮”的旧语义（§11.2 修复回路）。
     */
    @Test
    void invalidJsonIsRepairedWithinSameRunThenCompletes() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // 第一次输出无法解析（坏 JSON），第二次携带错误摘要重新输出有效的 MainTurnResult。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("这不是结构化结果"),
                answer("修复后回复。\n{\"action\":\"CHAT\",\"expertCalls\":[]}")));
        ObjectProvider<ChatModel> modelProvider = provider(model);

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity(5L, "ACCEPTED", "knowledge-task-conversation-500");
        run.setAgentName("knowledge-curator");
        when(runs.markKnowledgeRunning(eq(5L), any())).thenReturn(1);
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
        KnowledgeCurationRunExecutor executor = executor(modelProvider, saver, definitions, runs,
                mock(KnowledgeTaskMessageMapper.class), mock(AgentEventService.class),
                mock(KnowledgeTaskEventService.class), scheduler, mock(KnowledgeTaskRunProjectionService.class));

        executor.start(run, "任意目标", definition());

        // 修复回路：坏输出先进入 fix 节点（记录错误），重新生成后正常完成；模型调用恰好 2 次。
        assertThat(model.calls()).isEqualTo(2);
        verify(runs).completeKnowledge(eq(5L), eq("修复后回复。"), any(int.class), any(int.class), any(int.class),
                any(long.class), isNull(), isNull(), any(Instant.class));
        System.out.printf("测试证据：场景=无效JSON修复回路，模型调用=%d（首次无效+修复后有效），最终回复=%s%n",
                model.calls(), "修复后回复。");
    }

    /**
     * 业务目的：重试耗尽（2 次修复仍无效）时 run 不落入失败终态，而是保留 Checkpoint 并以包含原因的可见说明结束本轮。
     * 防止无效输出把整轮升级为一次性失败（§11.4 重试耗尽）。
     */
    @Test
    void retryExhaustionEndsTurnWithVisibleRecoveryMessage() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                answer("坏结果1"), answer("坏结果2"), answer("坏结果3")));
        ObjectProvider<ChatModel> modelProvider = provider(model);

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity(6L, "ACCEPTED", "knowledge-task-conversation-600");
        run.setAgentName("knowledge-curator");
        when(runs.markKnowledgeRunning(eq(6L), any())).thenReturn(1);
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
        KnowledgeCurationRunExecutor executor = executor(modelProvider, saver, definitions, runs,
                mock(KnowledgeTaskMessageMapper.class), mock(AgentEventService.class),
                mock(KnowledgeTaskEventService.class), scheduler, mock(KnowledgeTaskRunProjectionService.class));

        when(runs.markKnowledgeRecovery(eq(6L), any())).thenReturn(1);
        executor.start(run, "任意目标", definition());

        // 第一次坏输出 → 修复(1) → 第二次坏输出 → 修复(2) → 恢复门；模型调用 2 次（两次坏输出）。
        // 重试耗尽不落失败终态：run 转为 WAITING_FOR_USER（保留 Checkpoint），可见回复写入会话消息。
        assertThat(model.calls()).isEqualTo(2);
        verify(runs).markKnowledgeRecovery(eq(6L), any(Instant.class));
        verify(runs, org.mockito.Mockito.never()).completeKnowledge(eq(6L), any(), any(int.class),
                any(int.class), any(int.class), any(long.class), any(), any(), any(Instant.class));
        System.out.printf("测试证据：场景=重试耗尽恢复门，模型调用=%d（2次无效后重试耗尽），run转为WAITING_FOR_USER而非失败%n",
                model.calls());
    }

    /**
     * 业务目的：主 Agent 直接调用专家（AgentTool 链路）时，只读查询只触发检索专家，不进入完整整理链路，
     * 专家以独立上下文运行，最终由主 Agent 输出 TURN_DONE 汇总。防止直调被当作完整整理想法式调用或
     * 让专家共享主 Agent 上下文（§6.2/§10.3）。
     */
    @Test
    void directExpertCallThroughAgentToolCompletesTurn() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .load().migrate();
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource())
                .createOption(CreateOption.CREATE_NONE).build();

        // main 第一次输出 toolCall(retriever) → AgentTool 执行检索（第 2 次调用）→ main 输出 TURN_DONE（第 3 次调用）。
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                toolCallAnswer("retriever", "{\"input\":\"查询当前草稿内容\"}"),
                answer("{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":710004,"
                        + "\"facts\":[{\"statement\":\"背景\",\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"检索到背景\"}"),
                answer("当前草稿事实已确认。\n{\"action\":\"TURN_DONE\",\"expertCalls\":[\"retriever\"]}")));
        ObjectProvider<ChatModel> modelProvider = provider(model);

        KnowledgeAgentDefinitionService definitions = mock(KnowledgeAgentDefinitionService.class);
        when(definitions.graphSpecs()).thenReturn(new KnowledgeCurationGraphFactory.AgentSpecSet(loadSpecs()));
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentRunEntity run = runEntity(7L, "ACCEPTED", "knowledge-task-conversation-700");
        run.setAgentName("knowledge-curator");
        when(runs.markKnowledgeRunning(eq(7L), any())).thenReturn(1);
        BoundedAgentRunScheduler scheduler = mock(BoundedAgentRunScheduler.class);
        when(scheduler.schedule(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
        KnowledgeCurationRunExecutor executor = executor(modelProvider, saver, definitions, runs,
                mock(KnowledgeTaskMessageMapper.class), mock(AgentEventService.class),
                mock(KnowledgeTaskEventService.class), scheduler, mock(KnowledgeTaskRunProjectionService.class));

        executor.start(run, "当前草稿有什么内容", definition());

        assertThat(model.calls()).isEqualTo(3);
        verify(runs).completeKnowledge(eq(7L), eq("当前草稿事实已确认。"), any(int.class), any(int.class),
                any(int.class), any(long.class), isNull(), isNull(), any(Instant.class));
        System.out.printf("测试证据：场景=专家直调(AgentTool)，模型调用=%d（主x2+检索x1），最终回复=%s%n",
                model.calls(), "当前草稿事实已确认。");
    }

    private KnowledgeCurationRunExecutor executor(
            ObjectProvider<ChatModel> modelProvider,
            PostgresSaver saver,
            KnowledgeAgentDefinitionService definitions,
            AgentRunMapper runs,
            KnowledgeTaskMessageMapper messages,
            AgentEventService events,
            KnowledgeTaskEventService taskEvents,
            BoundedAgentRunScheduler scheduler,
            KnowledgeTaskRunProjectionService projection
    ) {
        Map<String, ToolCallback> callbacks = ALL_TOOLS.stream().collect(Collectors.toMap(
                n -> n, KnowledgeCurationConversationStateIT::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        return new KnowledgeCurationRunExecutor(
                modelProvider, properties(), resolver, saver, definitions, new ObjectMapper(),
                runs, mock(KnowledgeTaskConversationMapper.class), messages, events, taskEvents,
                mock(KnowledgeToolInvocationService.class), projection, scheduler, ContextAssemblyFixtures.budget(), Clock.systemUTC(),
                new MemoryPreloadSupply(mock(io.github.loredock.memory.api.MemoryService.class)));
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

    private AgentRunEntity runEntity(Long id, String status, String threadId) {
        return AgentRunEntity.builder()
                .id(id).operatorId("admin").projectIdentifier("atlas")
                .knowledgeTaskConversationId(100L).taskType("knowledge_curation")
                .agentName("knowledge-curator").status(status)
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

    private static ChatResponse answer(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder().build());
    }

    /** @return 构造带框架工具调用（AgentTool）应答，用于驱动主 Agent 的专家直调链路。 */
    private static ChatResponse toolCallAnswer(String toolName, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", toolName, arguments)))
                .build())), ChatResponseMetadata.builder().build());
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
