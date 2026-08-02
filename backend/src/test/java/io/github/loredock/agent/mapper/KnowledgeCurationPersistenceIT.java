package io.github.loredock.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.service.AgentEventService;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.service.KnowledgeTaskRunProjectionService;
import io.github.loredock.agent.scheduler.AgentRunRecovery;
import io.github.loredock.agent.service.KnowledgeCurationRunExecutor;
import io.github.loredock.knowledge.api.KnowledgeDraftException;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import java.util.List;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeCurationPersistenceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Long PROJECT_A_ID = 762818992519970801L;
    private static final Long PROJECT_B_ID = 762818992519970802L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_knowledge_curation_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired(required = false)
    private KnowledgeTaskService tasks;

    @Autowired(required = false)
    private KnowledgeDraftService drafts;

    @Autowired
    private AgentEventService agentEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KnowledgeTaskRunProjectionService runProjection;

    @Autowired
    private AgentRunRecovery shortRunRecovery;

    /** 持久化契约测试显式隔离后台模型执行，状态推进由各用例确定性控制。 */
    @MockitoBean
    private KnowledgeCurationRunExecutor executor;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "member");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "成员");
        registry.add("loredock.identity.web.accounts[1].role", () -> "MEMBER");
        registry.add("loredock.identity.web.accounts[1].password-hash", () -> BCRYPT_HASH);
    }

    @BeforeEach
    void resetFacts() {
        if (tableExists("knowledge_task_conversation")) {
            jdbc.update("update knowledge_task_conversation set current_draft_id = null");
        }
        for (String table : List.of(
                "knowledge_draft_revision_source", "knowledge_draft_revision", "knowledge_draft",
                "knowledge_task_message", "knowledge_task_selected_draft", "agent_evidence", "agent_run_event", "agent_run",
                "knowledge_task_conversation", "knowledge_document", "project_branch", "project_space")) {
            if (tableExists(table)) {
                jdbc.update("delete from " + table);
            }
        }
        seedProject(PROJECT_A_ID, "atlas");
        seedProject(PROJECT_B_ID, "borealis");
        seedInputDraft(PROJECT_A_ID, "atlas");
        seedInputDraft(PROJECT_B_ID, "borealis");
    }

    /**
     * 业务目的：Flyway 必须建立会话、消息和不可变草稿修订结构，并把知识 run 关联回既有 agent_run；
     * 防止实现绕过迁移在运行时建表，或为长任务复制第二套通用运行表。
     */
    @Test
    void flywayCreatesCurationTablesAndExtendsExistingAgentRun() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = current_schema()
                  and table_name in (
                    'knowledge_task_conversation', 'knowledge_task_message', 'knowledge_task_selected_draft', 'knowledge_draft',
                    'knowledge_draft_revision', 'knowledge_draft_revision_source')
                order by table_name
                """, String.class);
        List<String> runColumns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = current_schema() and table_name = 'agent_run'
                  and column_name in ('knowledge_task_conversation_id', 'thread_id', 'checkpoint_saved_at')
                order by column_name
                """, String.class);

        assertThat(tables).hasSize(6);
        assertThat(runColumns).containsExactly(
                "checkpoint_saved_at", "knowledge_task_conversation_id", "thread_id");
        System.out.printf("测试证据：场景=T6B Flyway，业务表=%d，agent_run扩展列=%s，数据库=PostgreSQL%n",
                tables.size(), runColumns);
    }

    /**
     * 业务目的：调度器响应不确定时以相同项目、窗口和幂等键重试只能得到同一会话和同一首轮 run，
     * 防止重复模型调用和重复草稿。
     */
    @Test
    void identicalSystemTriggerReturnsSameConversationAndRun() {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        KnowledgeTaskService.StartRequest request = start(
                "weekly:atlas:2026-W31", "system:scheduler", "atlas", KnowledgeTaskService.TriggerType.SYSTEM);

        KnowledgeTaskService.KnowledgeTask first = tasks.start(request);
        KnowledgeTaskService.KnowledgeTask retried = tasks.start(request);

        assertThat(retried.conversationId()).isEqualTo(first.conversationId());
        assertThat(retried.runs()).extracting(KnowledgeTaskService.KnowledgeTaskRun::runId)
                .containsExactlyElementsOf(first.runs().stream()
                        .map(KnowledgeTaskService.KnowledgeTaskRun::runId).toList());
        assertThat(count("knowledge_task_conversation")).isEqualTo(1);
        assertThat(count("agent_run")).isEqualTo(1);
        System.out.printf("测试证据：场景=系统触发幂等，会话=%s，run=%s，会话/运行=1/1%n",
                first.conversationId(), first.runs().getFirst().runId());
    }

    /**
     * 业务目的：点击合并时必须冻结所选草稿的正文和修订，原文之后被编辑也不能改变运行输入；
     * 防止管理员审核的 Diff 基于一组悄然变化、无法复现的材料。
     */
    @Test
    void selectedDraftContentIsFrozenWhenTheConversationStarts() {
        Long documentId = inputDraftId("atlas");
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "frozen-input", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));

        jdbc.update("update knowledge_document set body = '# 后续编辑', revision = 2 where id = ?", documentId);
        String frozen = jdbc.queryForObject("""
                select markdown from knowledge_task_selected_draft
                where conversation_id = ? and document_id = ?
                """, String.class, task.conversationId(), documentId);
        Long frozenRevision = jdbc.queryForObject("""
                select document_revision from knowledge_task_selected_draft
                where conversation_id = ? and document_id = ?
                """, Long.class, task.conversationId(), documentId);

        assertThat(frozen).isEqualTo("# 候选业务知识");
        assertThat(frozenRevision).isEqualTo(1L);
        assertThat(tasks.get(task.conversationId(), "admin").selectedDrafts())
                .extracting(KnowledgeTaskService.SelectedDraft::documentId).containsExactly(documentId);
        System.out.printf("测试证据：场景=固定勾选输入，会话=%s，文档=%s，固定修订=%d，原文当前修订=2%n",
                task.conversationId(), documentId, frozenRevision);
    }

    /**
     * 业务目的：一次整理只能选择同一项目的 Markdown DRAFT，非法选择必须在创建会话前整体拒绝；
     * 防止跨项目知识泄露或留下没有有效输入的空运行。
     */
    @Test
    void rejectsCrossProjectDraftSelectionBeforeCreatingConversation() {
        KnowledgeTaskService.StartRequest request = new KnowledgeTaskService.StartRequest(
                "cross-project", "admin", "atlas", List.of(inputDraftId("borealis")),
                KnowledgeTaskService.TriggerType.MANUAL, "测试触发", "knowledge-curator", "整理项目知识");

        assertThatThrownBy(() -> tasks.start(request))
                .isInstanceOfSatisfying(KnowledgeTaskRequestException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_DRAFT_SELECTION_INVALID));
        assertThat(count("knowledge_task_conversation")).isZero();
        assertThat(count("agent_run")).isZero();
        System.out.println("测试证据：场景=跨项目草稿选择，创建会话=0，创建run=0，结果=已拒绝");
    }

    /**
     * 业务目的：不同操作者或项目的知识任务不得通过会话标识互相读取，防止长期任务消息、来源和草稿串线。
     */
    @Test
    void conversationsRemainIsolatedAcrossOperatorsAndProjects() {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        KnowledgeTaskService.KnowledgeTask atlas = tasks.start(start(
                "manual-atlas", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        KnowledgeTaskService.KnowledgeTask borealis = tasks.start(start(
                "manual-borealis", "member", "borealis", KnowledgeTaskService.TriggerType.MANUAL));

        assertThatThrownBy(() -> tasks.get(atlas.conversationId(), "member"))
                .isInstanceOfSatisfying(KnowledgeTaskRequestException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                KnowledgeTaskRequestException.Code.KNOWLEDGE_TASK_NOT_FOUND));
        assertThat(tasks.get(atlas.conversationId(), "admin").projectIdentifier()).isEqualTo("atlas");
        assertThat(tasks.get(borealis.conversationId(), "member").projectIdentifier()).isEqualTo("borealis");
        System.out.printf("测试证据：场景=知识会话隔离，atlas会话=%s，borealis会话=%s，跨用户可见=0%n",
                atlas.conversationId(), borealis.conversationId());
    }

    /**
     * 业务目的：草稿成功更新必须只增加一个修订，使用过期基础修订的并发写入必须原子失败，
     * 防止 Agent 静默覆盖管理员或另一轮运行的新内容。
     */
    @Test
    void draftRevisionIncreasesOnceAndRejectsStaleBase() {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        assertThat(drafts).as("知识草稿 Service 尚未实现").isNotNull();
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "draft-revision", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        Long runId = task.runs().getFirst().runId();
        KnowledgeDraftService.AccessContext context = context(task, runId);
        KnowledgeDraftService.DraftRevision created = drafts.create(
                new KnowledgeDraftService.CreateRequest(context, "create-1", "Atlas 约定", null));
        KnowledgeDraftService.UpdateRequest firstWrite = update(
                context, created.draftId(), created.revision(), "update-1", "# 背景\n");

        KnowledgeDraftService.DraftRevision revisionOne = drafts.update(firstWrite);
        List<KnowledgeDraftService.DraftRevision> history = drafts.list(
                new KnowledgeDraftService.ReadRequest(context, created.draftId(), null));

        assertThat(revisionOne.revision()).isEqualTo(created.revision() + 1);
        assertThat(history).extracting(KnowledgeDraftService.DraftRevision::revision)
                .containsExactly(created.revision(), revisionOne.revision());
        assertThatThrownBy(() -> drafts.update(update(
                context, created.draftId(), created.revision(), "update-stale", "# 冲突内容\n")))
                .isInstanceOfSatisfying(KnowledgeDraftException.class, failure ->
                        assertThat(failure.code()).isEqualTo(KnowledgeDraftException.Code.DRAFT_REVISION_CONFLICT));
        assertThat(count("knowledge_draft_revision")).isEqualTo(2);
        System.out.printf("测试证据：场景=草稿乐观修订，draft=%s，基线=%d，当前=%d，历史修订=%d，过期写入=已拒绝%n",
                created.draftId(), created.revision(), revisionOne.revision(), history.size());
    }

    /**
     * 业务目的：Checkpoint 恢复重放相同 draft_update 时必须返回原修订且不重复来源关系；
     * 同一个幂等键携带不同输入必须明确冲突。
     */
    @Test
    void draftToolRetryIsIdempotentAndSameKeyDifferentInputConflicts() {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        assertThat(drafts).as("知识草稿 Service 尚未实现").isNotNull();
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "draft-retry", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        KnowledgeDraftService.AccessContext context = context(task, task.runs().getFirst().runId());
        KnowledgeDraftService.DraftRevision created = drafts.create(
                new KnowledgeDraftService.CreateRequest(context, "create-retry", "Atlas 约定", null));
        KnowledgeDraftService.UpdateRequest request = update(
                context, created.draftId(), created.revision(), "tool-call-7", "# 背景\n");

        KnowledgeDraftService.DraftRevision first = drafts.update(request);
        KnowledgeDraftService.DraftRevision replayed = drafts.update(request);

        assertThat(replayed.revision()).isEqualTo(first.revision());
        assertThat(count("knowledge_draft_revision")).isEqualTo(2);
        assertThat(count("knowledge_draft_revision_source")).isEqualTo(first.sources().size());
        assertThatThrownBy(() -> drafts.update(update(
                context, created.draftId(), created.revision(), "tool-call-7", "# 不同内容\n")))
                .isInstanceOfSatisfying(KnowledgeDraftException.class, failure ->
                        assertThat(failure.code()).isEqualTo(KnowledgeDraftException.Code.DRAFT_IDEMPOTENCY_CONFLICT));
        System.out.printf("测试证据：场景=Tool重放，draft=%s，返回修订=%d，修订总数=2，重复来源=0%n",
                created.draftId(), replayed.revision());
    }

    /**
     * 业务目的：管理员查看某一修订 Diff 后若 Agent 又产生新修订，旧审核请求必须拒绝，
     * 防止发布用户从未查看过的内容或静默回退当前草稿。
     */
    @Test
    void publicationRejectsReviewedRevisionAfterDraftChanged() {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        assertThat(drafts).as("知识草稿 Service 尚未实现").isNotNull();
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "publish-conflict", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        KnowledgeDraftService.AccessContext context = context(task, task.runs().getFirst().runId());
        KnowledgeDraftService.DraftRevision created = drafts.create(
                new KnowledgeDraftService.CreateRequest(context, "create-publish", "Atlas 约定", null));
        KnowledgeDraftService.DraftRevision reviewed = drafts.update(update(
                context, created.draftId(), created.revision(), "update-reviewed", "# 已审核\n"));
        KnowledgeDraftService.DraftDiff diff = drafts.diff(new KnowledgeDraftService.DiffRequest(
                context, created.draftId(), null, reviewed.revision()));
        KnowledgeDraftService.DraftRevision changed = drafts.update(update(
                context, created.draftId(), reviewed.revision(), "update-after-review", "# 新内容\n"));

        assertThat(diff.toRevision()).isEqualTo(reviewed.revision());
        assertThat(changed.revision()).isGreaterThan(reviewed.revision());
        assertThatThrownBy(() -> drafts.publish(new KnowledgeDraftService.PublishRequest(
                context, created.draftId(), reviewed.revision())))
                .isInstanceOfSatisfying(KnowledgeDraftException.class, failure ->
                        assertThat(failure.code()).isEqualTo(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT));
        System.out.printf("测试证据：场景=发布修订锁定，已审核=%d，当前=%d，旧审核发布=已拒绝%n",
                reviewed.revision(), changed.revision());
    }

    /**
     * 业务目的：管理员只有在当前修订与已查看 Diff 完全一致时才能发布，且发布结果必须进入现有正式知识生命周期；
     * 防止绕过正式文档状态机，或只给草稿打上“已发布”标记却没有可浏览的正式知识。
     */
    @Test
    void reviewedCurrentRevisionPublishesThroughExistingKnowledgeLifecycle() {
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "publish-reviewed", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        KnowledgeDraftService.AccessContext context = context(task, task.runs().getFirst().runId());
        KnowledgeDraftService.DraftRevision created = drafts.create(
                new KnowledgeDraftService.CreateRequest(context, "create-reviewed", "Atlas 审核约定", null));
        KnowledgeDraftService.DraftRevision reviewed = drafts.update(update(
                context, created.draftId(), created.revision(), "update-current", "# 已审核正式内容\n"));
        KnowledgeDraftService.DraftDiff diff = drafts.diff(new KnowledgeDraftService.DiffRequest(
                context, created.draftId(), null, reviewed.revision()));

        KnowledgeDraftService.Publication publication = drafts.publish(new KnowledgeDraftService.PublishRequest(
                context, created.draftId(), diff.toRevision()));

        assertThat(publication.revision()).isEqualTo(reviewed.revision());
        assertThat(jdbc.queryForObject(
                "select status from knowledge_document where id = ?", String.class, publication.documentId()))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                "select body from knowledge_document where id = ?", String.class, publication.documentId()))
                .isEqualTo(reviewed.markdown());
        assertThat(jdbc.queryForObject(
                "select published_revision from knowledge_draft where id = ?", Long.class, created.draftId()))
                .isEqualTo(reviewed.revision());
        System.out.printf("测试证据：场景=审核后发布，draft=%s，修订=%d，正式文档=%s，状态=PUBLISHED%n",
                created.draftId(), publication.revision(), publication.documentId());
    }

    /**
     * 业务目的：页面过程必须来自服务端真实运行事件并只包含类型化公开字段；
     * 防止把模型输出的“我已调用 Tool”文本当作状态事实，或把 Tool 原始 JSON 暴露给浏览器。
     */
    @Test
    void snapshotProjectsOnlyCommittedTypedRunEvents() {
        KnowledgeTaskService.KnowledgeTask started = tasks.start(start(
                "event-projection", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        Long runId = started.runs().getFirst().runId();
        jdbc.update("update agent_run set status = 'RUNNING', started_at = now() where id = ?", runId);
        AgentEvent.Payload payload = new AgentEvent.Payload(
                "RETRIEVING", "knowledge_search", "检索项目知识", "项目=atlas", "命中 2 条",
                2, 12L, "COMPLETED", List.of(), null, null, null, null, false, false);
        assertThat(agentEvents.append(runId, AgentEventType.TOOL_COMPLETED,
                AgentEvent.SubjectType.TOOL, payload, java.time.Instant.now())).isTrue();

        KnowledgeTaskService.KnowledgeTask snapshot = tasks.get(started.conversationId(), "admin");

        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.events().getFirst().payload().name()).isEqualTo("knowledge_search");
        assertThat(snapshot.events().getFirst().payload().resultSummary()).isEqualTo("命中 2 条");
        assertThat(snapshot.messages()).noneMatch(message -> message.content().contains("命中 2 条"));
        System.out.printf("测试证据：场景=安全事件投影，run=%s，事件=%s，公开结果数=%d，Tool原文消息=0%n",
                runId, snapshot.events().getFirst().type(), snapshot.events().getFirst().payload().count());
    }

    /**
     * 业务目的：系统首轮必须先保存可见触发消息；一轮正常完成后的追加指导必须创建独立新 run，
     * 防止覆盖上一轮审计、资源统计或 Checkpoint。
     */
    @Test
    void systemFirstMessageAndCompletedFollowUpUseIndependentRuns() {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        KnowledgeTaskService.KnowledgeTask first = tasks.start(start(
                "system-first", "system:scheduler", "atlas", KnowledgeTaskService.TriggerType.SYSTEM));
        KnowledgeTaskService.KnowledgeTaskRun firstRun = first.runs().getFirst();

        assertThat(first.messages()).isNotEmpty();
        assertThat(first.messages().getFirst().role()).isEqualTo(KnowledgeTaskService.MessageRole.SYSTEM_TRIGGER);
        jdbc.update("update agent_run set status = 'COMPLETED', finished_at = now() where id = ?", firstRun.runId());

        KnowledgeTaskService.KnowledgeTaskRun continued = tasks.continueTask(
                new KnowledgeTaskService.ContinueRequest(
                        first.conversationId(), "system:scheduler", "follow-up-1", "删除没有双来源支持的建议"));
        KnowledgeTaskService.KnowledgeTask snapshot = tasks.get(first.conversationId(), "system:scheduler");

        assertThat(continued.runId()).isNotEqualTo(firstRun.runId());
        assertThat(continued.threadId()).isNotEqualTo(firstRun.threadId());
        assertThat(snapshot.runs()).hasSize(2);
        assertThat(snapshot.messages()).extracting(KnowledgeTaskService.KnowledgeTaskMessage::role)
                .contains(KnowledgeTaskService.MessageRole.SYSTEM_TRIGGER, KnowledgeTaskService.MessageRole.USER);
        System.out.printf("测试证据：场景=完成后继续，会话=%s，旧run=%s，新run=%s，历史run=2%n",
                first.conversationId(), firstRun.runId(), continued.runId());
    }

    /**
     * 业务目的：暂停后的指导必须沿用同一 threadId 从 PostgreSQL Checkpoint 恢复，且进程重建后仍可读取；
     * 防止把指导误当作完成后的新 run，或仅靠内存保存暂停事实。
     */
    @Test
    void waitingGuidanceResumesSameRunFromRestartedPostgresCheckpoint() throws Exception {
        assertThat(tasks).as("知识任务 Service 尚未实现").isNotNull();
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "pause-resume", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        KnowledgeTaskService.KnowledgeTaskRun running = task.runs().getFirst();
        jdbc.update("update agent_run set status = 'RUNNING', started_at = now() where id = ?", running.runId());
        KnowledgeTaskService.KnowledgeTaskRun requested = tasks.requestPause(
                new KnowledgeTaskService.PauseRequest(running.runId(), "admin"));
        RunnableConfig config = RunnableConfig.builder().threadId(running.threadId()).build();
        PostgresSaver firstProcess = checkpointSaver();
        firstProcess.put(config, Checkpoint.builder()
                .id("d2f62f10-4f19-4ad7-b12a-268df9bf8f02")
                .nodeId("safe_boundary")
                .nextNodeId("human_feedback")
                .state(Map.of("pauseRequested", true))
                .build());
        assertThat(runProjection.markWaitingAfterInterrupt(running.runId())).isTrue();

        PostgresSaver restartedProcess = checkpointSaver();
        Checkpoint recovered = restartedProcess.get(config).orElseThrow();
        KnowledgeTaskService.KnowledgeTaskRun resumed = tasks.resume(new KnowledgeTaskService.ResumeRequest(
                running.runId(), "admin", "优先核对适用版本，不要直接合并"));
        KnowledgeTaskService.KnowledgeTask snapshot = tasks.get(task.conversationId(), "admin");

        assertThat(requested.status()).isEqualTo(KnowledgeTaskService.RunStatus.PAUSE_REQUESTED);
        assertThat(recovered.getNextNodeId()).isEqualTo("human_feedback");
        assertThat(resumed.runId()).isEqualTo(running.runId());
        assertThat(resumed.threadId()).isEqualTo(running.threadId());
        assertThat(snapshot.messages()).extracting(KnowledgeTaskService.KnowledgeTaskMessage::content)
                .contains("优先核对适用版本，不要直接合并");
        System.out.printf("测试证据：场景=Checkpoint指导恢复，run=%s，threadId=%s，恢复节点=%s，新run=0%n",
                resumed.runId(), resumed.threadId(), recovered.getNextNodeId());
    }

    /**
     * 业务目的：后端启动时的短运行恢复器不得终结带真实 Checkpoint 的知识整理运行；
     * 防止 project_qa 的不可恢复语义误伤可继续的长任务。
     */
    @Test
    void shortRunRecoveryLeavesCheckpointedKnowledgeRunRecoverable() throws Exception {
        KnowledgeTaskService.KnowledgeTask task = tasks.start(start(
                "restart-split", "admin", "atlas", KnowledgeTaskService.TriggerType.MANUAL));
        KnowledgeTaskService.KnowledgeTaskRun run = task.runs().getFirst();
        jdbc.update("update agent_run set status = 'RUNNING', started_at = now() where id = ?", run.runId());
        RunnableConfig config = RunnableConfig.builder().threadId(run.threadId()).build();
        checkpointSaver().put(config, Checkpoint.builder()
                .id("8e6a41cd-fcd3-42e8-a88d-71c7154c26a1")
                .nodeId("safe_boundary")
                .nextNodeId("agent")
                .state(Map.of("recoverable", true))
                .build());

        shortRunRecovery.run(new DefaultApplicationArguments());

        KnowledgeTaskService.KnowledgeTaskRun recovered = tasks.get(task.conversationId(), "admin")
                .runs().getFirst();
        assertThat(recovered.status()).isEqualTo(KnowledgeTaskService.RunStatus.RUNNING);
        assertThat(checkpointSaver().get(config)).isPresent();
        System.out.printf("测试证据：场景=重启恢复分流，run=%s，taskType=knowledge_curation，状态=%s，checkpoint=1%n",
                recovered.runId(), recovered.status());
    }

    private PostgresSaver checkpointSaver() throws Exception {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .createOption(CreateOption.CREATE_NONE)
                .build();
    }

    private KnowledgeTaskService.StartRequest start(
            String key,
            String operator,
            String project,
            KnowledgeTaskService.TriggerType trigger
    ) {
        return new KnowledgeTaskService.StartRequest(
                key, operator, project, List.of(inputDraftId(project)), trigger,
                "测试触发", "knowledge-curator", "整理项目知识");
    }

    private KnowledgeDraftService.AccessContext context(
            KnowledgeTaskService.KnowledgeTask task,
            Long runId
    ) {
        return new KnowledgeDraftService.AccessContext(
                "admin", task.projectIdentifier(), task.conversationId(), runId);
    }

    private KnowledgeDraftService.UpdateRequest update(
            KnowledgeDraftService.AccessContext context,
            Long draftId,
            long baseRevision,
            String key,
            String markdown
    ) {
        return new KnowledgeDraftService.UpdateRequest(
                context,
                draftId,
                baseRevision,
                key,
                List.of(new KnowledgeDraftService.UpdateOperation(
                        KnowledgeDraftService.OperationType.INSERT_AFTER, null, markdown, List.of())),
                "结构调整");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from information_schema.tables
                    where table_schema = current_schema() and table_name = ?
                )
                """, Boolean.class, table));
    }

    private void seedProject(Long id, String identifier) {
        jdbc.update("""
                insert into project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '', 'Java', 'ENABLED', now(), now(), 'test', 'test')
                """, id, identifier, identifier);
        jdbc.update("""
                insert into project_branch(
                    project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, 'main', now(), now(), 'test', 'test')
                """, id);
    }

    private void seedInputDraft(Long projectId, String identifier) {
        jdbc.update("""
                insert into knowledge_document(
                    format, title, body, directory_path, tags, scope_type, project_id, branch_id,
                    source_type, wiki_url, original_filename, curation_note, status, revision,
                    created_at, updated_at, created_by, updated_by)
                values ('MARKDOWN', ?, '# 候选业务知识', '待处理', '[]', 'PROJECT', ?, null,
                        'UPLOAD', null, ?, null, 'DRAFT', 1, now(), now(), 'test', 'test')
                """, identifier + " 候选知识", projectId, identifier + ".md");
    }

    private Long inputDraftId(String projectIdentifier) {
        return jdbc.queryForObject("""
                select d.id from knowledge_document d
                join project_space p on p.id = d.project_id
                where p.identifier = ? and d.status = 'DRAFT'
                order by d.id limit 1
                """, Long.class, projectIdentifier);
    }
}
