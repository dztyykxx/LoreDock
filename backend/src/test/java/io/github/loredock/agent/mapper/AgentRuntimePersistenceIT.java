package io.github.loredock.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.model.result.ProjectQaModelResult;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.model.snapshot.EvidenceSourceMetadata;
import io.github.loredock.agent.scheduler.AgentRunRecovery;
import io.github.loredock.agent.service.AgentEventService;
import io.github.loredock.agent.service.AgentEvidenceService;
import io.github.loredock.agent.service.AgentRunService;
import io.github.loredock.agent.service.AgentRuntime;
import io.github.loredock.agent.service.ProjectQaRunTaskExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AgentRuntimePersistenceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Long PROJECT_ID = 2891640495451214098L;
    private static final Long BRANCH_ID = 916404954512140971L;
    private static final Long DOCUMENT_ID = 6241483468158498680L;
    private static final Long SNAPSHOT_ID = 2133123963609712777L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_agent_persistence_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private AgentRunService runs;
    @Autowired private AgentEventService events;
    @Autowired private AgentEvidenceService evidence;
    @Autowired private AgentRunRecovery recovery;
    @Autowired private ProjectQaResultConverter validator;
    @Autowired private Clock timeProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

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
    void resetRuntimeFacts() {
        jdbcTemplate.update("delete from agent_evidence");
        jdbcTemplate.update("delete from agent_run_event");
        jdbcTemplate.update("delete from agent_run");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from code_snapshot");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        jdbcTemplate.update("delete from stored_object");
        seedScopeAndSkill();
    }

    /**
     * 业务目的：SSE 只能收到事务提交后的实时事件，回滚事件既不能通知连接也不能留在续读表中。
     */
    @Test
    void liveSubscriptionPublishesOnlyCommittedEvents() throws Exception {
        Long runId = 8000000000000000206L;
        runs.insert(createData(runId, "live-event-key"));
        try (var subscription = events.subscribe(runId)) {
            events.append(runId, AgentEventType.MODEL_STARTED, "model", Instant.now());
            var committed = subscription.poll(Duration.ofSeconds(1));

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                events.append(runId, AgentEventType.SOURCE_FOUND, "knowledge_search count=1", Instant.now());
                status.setRollbackOnly();
            });
            var rolledBack = subscription.poll(Duration.ofMillis(100));

            assertThat(committed).isNotNull();
            assertThat(committed.type()).isEqualTo(AgentEventType.MODEL_STARTED);
            assertThat(rolledBack).isNull();
            assertThat(events.findAfter(runId, committed.sequence(), 20)).isEmpty();
            System.out.printf("测试证据：场景=SSE提交后通知，runId=%s，提交事件=%s，回滚通知=0，回滚落库=0%n",
                    runId, committed.type());
        }
    }

    /**
     * 业务目的：同一操作者并发提交同一幂等键时只能产生一个运行事实，防止重复调用模型。
     */
    @Test
    void concurrentIdempotentInsertPersistsExactlyOneRun() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> insertAfter(start, createData(8000000000000000204L, "same-key")));
            Future<Object> second = executor.submit(() -> insertAfter(start, createData(8000000000000000205L, "same-key")));
            start.countDown();
            results.add(first.get());
            results.add(second.get());
        }

        assertThat(results.stream().filter(value -> value instanceof Long)).hasSize(1);
        assertThat(results.stream().filter(value -> value instanceof DataIntegrityViolationException)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from agent_run", Integer.class)).isEqualTo(1);
        System.out.println("测试证据：场景=并发幂等插入，成功运行数=1，唯一约束冲突数=1");
    }

    /**
     * 业务目的：运行受理与首条公开事件必须在同一短事务提交，调度开始前两项事实都可查询。
     */
    @Test
    void acceptanceCommitsRunAndFirstEventTogether() {
        var accepted = runs.accept(createData(null, "acceptance-key"));
        Long runId = accepted.snapshot().runId();

        assertThat(accepted.snapshot().status()).isEqualTo(AgentRunStatus.ACCEPTED);
        assertThat(accepted.newlyAccepted()).isTrue();
        assertThat(runs.findById(runId)).isPresent();
        assertThat(events.findAfter(runId, 0, 10)).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.type()).isEqualTo(AgentEventType.RUN_ACCEPTED);
        });
        System.out.printf("测试证据：场景=受理短事务，runId=%s，状态=%s，首事件=RUN_ACCEPTED#1%n",
                runId, accepted.snapshot().status());
    }

    /**
     * 业务目的：运行状态只能比较更新一次，超时后到达的回答与引用必须一起被丢弃。
     */
    @Test
    void terminalCompareAndSetRejectsLateAnswerAndCitations() {
        Long runId = 8000000000000000207L;
        runs.insert(createData(runId, "terminal-key"));
        Instant startedAt = Instant.parse("2026-07-30T01:00:01Z");

        assertThat(runs.markRunning(runId, startedAt)).isTrue();
        assertThat(runs.markRunning(runId, startedAt.plusSeconds(1))).isFalse();
        assertThat(runs.finishWithError(runId, AgentErrorCode.AGENT_RUN_TIMEOUT, true,
                AgentExecutionUsage.none(), startedAt.plusSeconds(2))).isTrue();
        assertThat(runs.complete(runId,
                new TrustedProjectQaResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        "迟到回答", null, List.of()),
                AgentExecutionUsage.none(), startedAt.plusSeconds(3))).isFalse();

        var snapshot = runs.findById(runId).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.TERMINATED);
        assertThat(snapshot.errorCode()).isEqualTo(AgentErrorCode.AGENT_RUN_TIMEOUT);
        assertThat(snapshot.citations()).isEmpty();
        System.out.printf("测试证据：场景=终态CAS，runId=%s，终态=%s，迟到引用数=%d%n",
                runId, snapshot.status(), snapshot.citations().size());
    }

    /**
     * 业务目的：运行已终止后的迟到模型或 Tool 进度不得继续追加，防止页面在失败事实后显示似乎仍在执行。
     */
    @Test
    void terminalRunRejectsLateTypedProcessEvent() {
        Long runId = 8000000000000000218L;
        runs.insert(createData(runId, "late-public-event-key"));
        Instant startedAt = Instant.parse("2026-07-30T01:00:01Z");
        assertThat(runs.markRunning(runId, startedAt)).isTrue();
        assertThat(runs.finishWithError(runId, AgentErrorCode.AGENT_RUN_TIMEOUT, true,
                AgentExecutionUsage.none(), startedAt.plusSeconds(2))).isTrue();
        long terminalSequence = events.lastSequence(runId);

        boolean appended = events.append(runId, AgentEventType.TOOL_COMPLETED, AgentEvent.SubjectType.TOOL,
                new AgentEvent.Payload("RETRIEVING", "knowledge_search", null, null, "迟到结果",
                        1, 3L, "COMPLETED", List.of(), null, null, null, null, false, false),
                startedAt.plusSeconds(3));

        assertThat(appended).isFalse();
        assertThat(events.lastSequence(runId)).isEqualTo(terminalSequence);
        assertThat(events.findAfter(runId, terminalSequence, 20)).isEmpty();
        System.out.printf("测试证据：场景=终态拒绝迟到公开事件，runId=%s，终态序号=%d，追加=false%n",
                runId, terminalSequence);
    }

    /**
     * 业务目的：多线程追加的公开事件必须先落库再按单调序号分页读取，断线续读不得重复。
     */
    @Test
    void concurrentEventsCommitBeforeMonotonicAfterSequenceReads() throws Exception {
        Long runId = 8000000000000000208L;
        runs.insert(createData(runId, "event-key"));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 20; index++) {
                int value = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return events.append(runId, AgentEventType.SOURCE_FOUND, "source-" + value, Instant.now()).sequence();
                }));
            }
            start.countDown();
            for (Future<Long> future : futures) {
                future.get();
            }
        }

        var firstPage = events.findAfter(runId, 0, 7);
        var secondPage = events.findAfter(runId, firstPage.getLast().sequence(), 2000);
        List<Long> sequences = new ArrayList<>();
        sequences.addAll(firstPage.stream().map(value -> value.sequence()).toList());
        sequences.addAll(secondPage.stream().map(value -> value.sequence()).toList());
        assertThat(sequences).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList());
        assertThat(firstPage).hasSize(7);
        assertThat(secondPage).hasSize(13);
        assertThat(events.findAfter(runId, 20, 5)).isEmpty();
        assertThat(events.lastSequence(runId)).isEqualTo(20);
        System.out.printf("测试证据：场景=事件提交后续读，runId=%s，序号范围=1..20，分页=7+13，末序号查询=20%n",
                runId);
    }

    /**
     * 业务目的：最终回答的引用只能指向同一运行已持久化的有限证据，且可完整往返 UTC 时间和可空 Token。
     */
    @Test
    void persistedEvidenceAndCitationRoundTripWithCompletedRun() {
        Long runId = 8000000000000000209L;
        Long evidenceId = 8000000000000000210L;
        Instant acceptedAt = Instant.parse("2026-07-30T02:00:00Z");
        runs.insert(createData(runId, "answer-key", acceptedAt));
        runs.markRunning(runId, acceptedAt.plusSeconds(1));
        evidence.saveAll(runId, List.of(new AgentEvidence(
                evidenceId, runId, EvidenceSourceType.KNOWLEDGE, true, 0.92,
                DOCUMENT_ID, null, "atlas", "main", null, null, "业务规则", acceptedAt,
                new EvidenceSourceMetadata("knowledge-source-v1", "PROJECT", "WIKI",
                        "https://example.test/wiki/rule", null))));

        AgentExecutionUsage usage = new AgentExecutionUsage(3, 2, 1, 0, null, null, 800);
        boolean completed = runs.complete(runId,
                new TrustedProjectQaResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        "有据可查的回答", null, List.of(evidenceId)),
                usage, acceptedAt.plusSeconds(2));

        var snapshot = runs.findById(runId).orElseThrow();
        assertThat(completed).isTrue();
        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(snapshot.answerBasis()).isEqualTo(AnswerBasis.BUSINESS_RULE);
        assertThat(snapshot.inputTokens()).isNull();
        assertThat(snapshot.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.evidenceId()).isEqualTo(evidenceId);
            assertThat(citation.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(citation.sourceMetadata().schemaVersion()).isEqualTo("knowledge-source-v1");
            assertThat(citation.sourceMetadata().wikiUrl()).isEqualTo("https://example.test/wiki/rule");
            assertThat(citation.order()).isEqualTo(1);
        });
        assertThat(evidence.findByRunId(runId)).singleElement().extracting(AgentEvidence::sourceUpdatedAt)
                .isEqualTo(acceptedAt);
        System.out.printf("测试证据：场景=证据与引用事务，runId=%s，终态=%s，引用数=%d，Token=未知%n",
                runId, snapshot.status(), snapshot.citations().size());
    }

    /**
     * 业务目的：迁移前没有 answer_basis 的可信回答必须只按最终引用类型稳定推导，防止历史页面显示错误依据。
     */
    @Test
    void legacyAnswerBasisIsDerivedFromFinalCitationTypes() {
        Long runId = 8000000000000000211L;
        AgentEvidence knowledge = knowledgeEvidence(runId, true);
        AgentEvidence code = codeEvidence(runId, true);
        Instant startedAt = Instant.parse("2026-07-30T02:30:00Z");
        runs.insert(createData(runId, "legacy-basis"));
        runs.markRunning(runId, startedAt);
        evidence.saveAll(runId, List.of(knowledge, code));
        runs.complete(runId, new TrustedProjectQaResult(
                        AgentResultType.ANSWER, AnswerBasis.MIXED, "混合回答", null,
                        List.of(knowledge.id(), code.id())),
                AgentExecutionUsage.none(), startedAt.plusSeconds(1));
        jdbcTemplate.update("update agent_run set answer_basis=null where id=?", runId);
        jdbcTemplate.update("update agent_evidence set metadata='{}'::jsonb where id=?", knowledge.id());

        var snapshot = runs.findById(runId).orElseThrow();

        assertThat(snapshot.answerBasis()).isEqualTo(AnswerBasis.MIXED);
        assertThat(snapshot.citations()).filteredOn(value -> value.sourceType() == EvidenceSourceType.KNOWLEDGE)
                .singleElement().extracting(value -> value.sourceMetadata().schemaVersion()).isNull();
        System.out.printf("测试证据：场景=历史回答依据与来源降级，runId=%s，引用类型=KNOWLEDGE+CODE，"
                + "basis=%s，历史metadata版本=null%n", runId, snapshot.answerBasis());
    }

    /**
     * 业务目的：进程重启必须把遗留的 ACCEPTED/RUNNING 单调终结为中断，重复恢复不得再追加事件。
     */
    @Test
    void recoveryTerminatesLegacyNonTerminalRunsExactlyOnce() throws Exception {
        Long acceptedRunId = 8000000000000000212L;
        Long runningRunId = 8000000000000000213L;
        Long completedRunId = 8000000000000000214L;
        runs.insert(createData(acceptedRunId, "recover-accepted"));
        runs.insert(createData(runningRunId, "recover-running"));
        runs.markRunning(runningRunId, Instant.parse("2026-07-30T00:00:01Z"));
        runs.insert(createData(completedRunId, "recover-completed"));
        runs.markRunning(completedRunId, Instant.parse("2026-07-30T00:00:01Z"));
        runs.finishWithError(completedRunId, AgentErrorCode.AGENT_MODEL_UNAVAILABLE, false,
                AgentExecutionUsage.none(), Instant.parse("2026-07-30T00:00:02Z"));

        recovery.run(new DefaultApplicationArguments());
        recovery.run(new DefaultApplicationArguments());

        assertThat(runs.findById(acceptedRunId).orElseThrow()).satisfies(run -> {
            assertThat(run.status()).isEqualTo(AgentRunStatus.TERMINATED);
            assertThat(run.errorCode()).isEqualTo(AgentErrorCode.AGENT_RUN_INTERRUPTED);
        });
        assertThat(runs.findById(runningRunId).orElseThrow().status()).isEqualTo(AgentRunStatus.TERMINATED);
        assertThat(runs.findById(completedRunId).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(events.findAfter(acceptedRunId, 0, 20)).singleElement()
                .extracting(event -> event.type()).isEqualTo(AgentEventType.RUN_TERMINATED);
        assertThat(events.findAfter(runningRunId, 0, 20))
                .filteredOn(event -> event.type() == AgentEventType.RUN_TERMINATED).singleElement()
                .extracting(event -> event.type()).isEqualTo(AgentEventType.RUN_TERMINATED);
        System.out.printf("测试证据：场景=重启恢复，accepted=%s，running=%s，terminal保持=%s，重复终结事件数=1%n",
                runs.findById(acceptedRunId).orElseThrow().status(),
                runs.findById(runningRunId).orElseThrow().status(),
                runs.findById(completedRunId).orElseThrow().status());
    }

    /**
     * 业务目的：Fake Model 产生的文档回答必须经过真实 PostgreSQL 证据外键与引用事务后才可发布。
     */
    @Test
    void fakeModelCompletesKnowledgeAnswerWithTrustedEvents() {
        Long runId = 8000000000000000215L;
        AgentEvidence knowledge = knowledgeEvidence(runId, true);
        String answer = "可信文档回答-".repeat(70);
        ProjectQaModelResult modelResult = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, answer, null, List.of(knowledge.id()));

        var snapshot = executeFake(runId, "trusted-knowledge", false, modelResult, List.of(knowledge));
        var published = events.findAfter(runId, 0, 200);

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(snapshot.resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(snapshot.answerBasis()).isEqualTo(AnswerBasis.BUSINESS_RULE);
        assertThat(snapshot.resultText()).isEqualTo(answer);
        assertThat(snapshot.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.sourceType()).isEqualTo(EvidenceSourceType.KNOWLEDGE);
            assertThat(citation.projectIdentifier()).isEqualTo("atlas");
            assertThat(citation.branch()).isEqualTo("main");
            assertThat(citation.sourceUpdatedAt()).isNotNull();
        });
        assertThat(published).noneMatch(event -> event.payload().contains(answer));
        assertThat(published).extracting(event -> event.sequence()).isSorted().doesNotHaveDuplicates();
        assertThat(published).extracting(event -> event.type()).containsSubsequence(
                AgentEventType.RUN_STARTED, AgentEventType.MODEL_STARTED)
                .endsWith(AgentEventType.RUN_COMPLETED);
        System.out.printf("测试证据：场景=Fake Model可信文档回答，项目=atlas，分支=main，证据=1，引用=%d，正文事件=0，状态=%s%n",
                snapshot.citations().size(), snapshot.status());
    }

    /**
     * 业务目的：伪造或被裁剪的引用即使来自模型最终文本，也只能落为稳定拒答，绝不能产生可信回答增量。
     */
    @Test
    void invalidModelCitationPersistsRefusalWithoutAnswerDelta() {
        Long runId = 8000000000000000216L;
        AgentEvidence trimmed = knowledgeEvidence(runId, false);
        ProjectQaModelResult forged = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "不可信模型答案", null,
                List.of(trimmed.id(), 8000000000000000217L));

        var snapshot = executeFake(runId, "invalid-citation", true, forged, List.of(trimmed));
        var published = events.findAfter(runId, 0, 200);

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(snapshot.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(snapshot.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
        assertThat(snapshot.resultText()).isEqualTo(ProjectQaResultConverter.REFUSAL_TEXT);
        assertThat(snapshot.citations()).isEmpty();
        assertThat(published).filteredOn(event -> event.type() == AgentEventType.RUN_COMPLETED)
                .singleElement().extracting(event -> event.payload())
                .isEqualTo(AgentResultType.REFUSAL.name());
        assertThat(published).noneMatch(event -> event.payload().contains(ProjectQaResultConverter.REFUSAL_TEXT));
        System.out.printf("测试证据：场景=非法引用降级拒答，runId=%s，reason=%s，正文事件=0，引用=0%n",
                runId, snapshot.refusalReason());
    }

    /**
     * 业务目的：证据不足、越界、遗留代码回答和文档冲突必须持久化稳定拒答原因与有限当前范围来源。
     */
    @Test
    void refusalMatrixPersistsStableReasonsAndCurrentScopeCitations() {
        Long insufficientRun = 8000000000000000218L;
        var insufficient = executeFake(insufficientRun, "insufficient", true,
                refusal(AgentRefusalReason.INSUFFICIENT_EVIDENCE, List.of()), List.of());
        Long outOfScopeRun = 8000000000000000219L;
        var outOfScope = executeFake(outOfScopeRun, "out-of-scope", true,
                refusal(AgentRefusalReason.OUT_OF_SCOPE, List.of()), List.of());
        Long legacyCodeRun = 8000000000000000220L;
        var legacyCode = executeFake(legacyCodeRun, "legacy-code", false,
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.CURRENT_IMPLEMENTATION,
                        "模型尝试回答实现", null, List.of()), List.of());
        Long conflictRun = 8000000000000000221L;
        AgentEvidence first = knowledgeEvidence(conflictRun, true);
        AgentEvidence second = knowledgeEvidence(conflictRun, true, 1_001);
        var conflict = executeFake(conflictRun, "conflict", false,
                new ProjectQaModelResult(AgentResultType.REFUSAL, AnswerBasis.BUSINESS_RULE,
                        ProjectQaResultConverter.REFUSAL_TEXT, AgentRefusalReason.SOURCE_CONFLICT,
                        List.of(first.id(), second.id())), List.of(first, second));

        assertThat(List.of(insufficient, outOfScope, legacyCode, conflict))
                .allSatisfy(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(snapshot.resultType()).isEqualTo(AgentResultType.REFUSAL);
                    assertThat(snapshot.resultText()).contains(ProjectQaResultConverter.REFUSAL_TEXT);
                });
        assertThat(insufficient.refusalReason()).isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(outOfScope.refusalReason()).isEqualTo(AgentRefusalReason.OUT_OF_SCOPE);
        assertThat(legacyCode.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
        assertThat(legacyCode.scope().branch()).isEqualTo("main");
        assertThat(legacyCode.scope().snapshotId()).isNull();
        assertThat(conflict.refusalReason()).isEqualTo(AgentRefusalReason.SOURCE_CONFLICT);
        assertThat(conflict.answerBasis()).isEqualTo(AnswerBasis.BUSINESS_RULE);
        assertThat(conflict.citations()).extracting(citation -> citation.sourceType())
                .containsExactly(EvidenceSourceType.KNOWLEDGE, EvidenceSourceType.KNOWLEDGE);
        System.out.printf("测试证据：场景=文档问答拒答矩阵，原因=%s，代码回答分支=%s，文档冲突引用=%d%n",
                List.of(insufficient.refusalReason(), outOfScope.refusalReason(), legacyCode.refusalReason(),
                        conflict.refusalReason()), legacyCode.scope().branch(), conflict.citations().size());
    }

    private Object insertAfter(CountDownLatch start, AgentRunCreateData data) throws InterruptedException {
        start.await();
        try {
            runs.insert(data);
            return data.runId();
        } catch (DataIntegrityViolationException exception) {
            return exception;
        }
    }

    private AgentRunCreateData createData(Long runId, String key) {
        return createData(runId, key, Instant.parse("2026-07-30T00:00:00Z"));
    }

    private AgentRunCreateData createData(Long runId, String key, Instant acceptedAt) {
        return new AgentRunCreateData(runId, "member", key, "c".repeat(64), "project_qa", "d".repeat(64), 12,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "deepseek-v4-flash", "project-qa-v1"),
                acceptedAt);
    }

    private AgentRunCreateData createDataWithSnapshot(Long runId, String key, Instant acceptedAt) {
        return new AgentRunCreateData(runId, "member", key, "c".repeat(64), "project_qa", "d".repeat(64), 12,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", SNAPSHOT_ID, "abcdef1", null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "deepseek-v4-flash", "project-qa-v1"),
                acceptedAt);
    }

    private io.github.loredock.agent.model.snapshot.AgentRunSnapshot executeFake(
            Long runId,
            String key,
            boolean withSnapshot,
            ProjectQaModelResult modelResult,
            List<AgentEvidence> ledger
    ) {
        Instant acceptedAt = timeProvider.instant();
        AgentRunCreateData data = withSnapshot
                ? createDataWithSnapshot(runId, key, acceptedAt) : createData(runId, key, acceptedAt);
        runs.insert(data);
        AgentRuntime fake = request -> {
            if (!ledger.isEmpty()) {
                evidence.saveAll(runId, ledger);
            }
            return new AgentExecutionResult(modelResult, ledger,
                    new AgentExecutionUsage(3, 2, 1, 0, 20L, 10L, 25));
        };
        ProjectQaRunTaskExecutor executor = new ProjectQaRunTaskExecutor(
                java.util.Optional.of(fake), runs, events, validator, timeProvider);
        executor.execute(new AgentExecutionRequest(runId, "仅存在于进程内的问题", "skill", "schema",
                data.scope(), data.versions(),
                new AgentRuntimeLimits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200),
                acceptedAt.plusSeconds(30)));
        return runs.findById(runId).orElseThrow();
    }

    private ProjectQaModelResult refusal(AgentRefusalReason reason, List<Long> citations) {
        return new ProjectQaModelResult(AgentResultType.REFUSAL, null,
                ProjectQaResultConverter.REFUSAL_TEXT, reason, citations);
    }

    private AgentEvidence knowledgeEvidence(Long runId, boolean retained) {
        return knowledgeEvidence(runId, retained, 1_000);
    }

    private AgentEvidence knowledgeEvidence(Long runId, boolean retained, long idOffset) {
        return new AgentEvidence(runId + idOffset, runId, EvidenceSourceType.KNOWLEDGE, retained, 0.9,
                DOCUMENT_ID, null, "atlas", "main", null, null, "业务规则", timeProvider.instant());
    }

    private AgentEvidence codeEvidence(Long runId, boolean retained) {
        return new AgentEvidence(runId + 2_000, runId, EvidenceSourceType.CODE, retained, 0.9,
                null, SNAPSHOT_ID, "atlas", "main", "abcdef1", "src/ReviewService.java", null,
                timeProvider.instant());
    }

    private void seedScopeAndSkill() {
        jdbcTemplate.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('skill-one', 'AVAILABLE', 'skill.md', 'text/markdown', 10, ?, now(), now(), 'test', 'test')
                """, "a".repeat(64));
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'atlas', 'Atlas', '', '', 'ENABLED', now(), now(), 'test', 'test')
                """, PROJECT_ID);
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', now(), now(), 'test', 'test')
                """, BRANCH_ID, PROJECT_ID);
        jdbcTemplate.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('public-code-fixture', 'AVAILABLE', 'public-code.zip', 'application/zip', 10,
                    ?, now(), now(), 'test', 'test')
                """, "b".repeat(64));
        jdbcTemplate.update("""
                insert into code_snapshot(id, project_id, branch_id, commit_hash, input_object_key, status,
                    indexed_file_count, ignored_file_count, indexed_at,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, 'abcdef1', 'public-code-fixture', 'ACTIVE', 3, 0, now(),
                    now(), now(), 'test', 'test')
                """, SNAPSHOT_ID, PROJECT_ID, BRANCH_ID);
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type, project_id,
                    branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'MARKDOWN', '业务规则', '公开模拟正文', '/', 'PROJECT', ?,
                    null, 'MANUAL', 'PUBLISHED', 1, now(), 'test', now(), now(), 'test', 'test')
                """, DOCUMENT_ID, PROJECT_ID);
    }
}
