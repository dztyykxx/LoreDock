package io.github.loredock.agent.infrastructure.persistence;

import io.github.loredock.agent.application.AgentEventRepository;
import io.github.loredock.agent.application.AgentExecutionPort;
import io.github.loredock.agent.application.AgentExecutionRequest;
import io.github.loredock.agent.application.AgentExecutionResult;
import io.github.loredock.agent.application.AgentExecutionUsage;
import io.github.loredock.agent.application.AgentEvidenceRepository;
import io.github.loredock.agent.application.AgentRunCreateData;
import io.github.loredock.agent.application.AgentRunAcceptanceService;
import io.github.loredock.agent.application.AgentRunRepository;
import io.github.loredock.agent.application.AgentRuntimeLimits;
import io.github.loredock.agent.application.AgentToolCallRepository;
import io.github.loredock.agent.application.ProjectQaRunTaskExecutor;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentEvidence;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.AnswerBasis;
import io.github.loredock.agent.domain.EvidenceSourceType;
import io.github.loredock.agent.domain.EvidenceSourceMetadata;
import io.github.loredock.agent.domain.ProjectQaModelResult;
import io.github.loredock.agent.domain.ProjectQaResultValidator;
import io.github.loredock.agent.domain.TrustedProjectQaResult;
import io.github.loredock.agent.infrastructure.runtime.AgentRunRecovery;
import io.github.loredock.platform.time.TimeProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AgentRuntimePersistenceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SKILL_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID SNAPSHOT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_agent_persistence_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private AgentRunRepository runs;
    @Autowired private AgentRunAcceptanceService acceptance;
    @Autowired private AgentEventRepository events;
    @Autowired private AgentEvidenceRepository evidence;
    @Autowired private AgentToolCallRepository toolCalls;
    @Autowired private AgentRunRecovery recovery;
    @Autowired private ProjectQaResultValidator validator;
    @Autowired private TimeProvider timeProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.mcp.token-sha256", () -> "a".repeat(64));
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
        jdbcTemplate.update("delete from agent_citation");
        jdbcTemplate.update("delete from agent_evidence");
        jdbcTemplate.update("delete from agent_tool_call");
        jdbcTemplate.update("delete from agent_run_event");
        jdbcTemplate.update("delete from agent_run");
        jdbcTemplate.update("delete from agent_skill_version");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from code_snapshot");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        jdbcTemplate.update("delete from stored_object");
        seedScopeAndSkill();
    }

    /**
     * 业务目的：同一操作者并发提交同一幂等键时只能产生一个运行事实，防止重复调用模型。
     */
    @Test
    void concurrentIdempotentInsertPersistsExactlyOneRun() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> insertAfter(start, createData(UUID.randomUUID(), "same-key")));
            Future<Object> second = executor.submit(() -> insertAfter(start, createData(UUID.randomUUID(), "same-key")));
            start.countDown();
            results.add(first.get());
            results.add(second.get());
        }

        assertThat(results.stream().filter(value -> value instanceof UUID)).hasSize(1);
        assertThat(results.stream().filter(value -> value instanceof DataIntegrityViolationException)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from agent_run", Integer.class)).isEqualTo(1);
        System.out.println("测试证据：场景=并发幂等插入，成功运行数=1，唯一约束冲突数=1");
    }

    /**
     * 业务目的：运行受理与首条公开事件必须在同一短事务提交，调度开始前两项事实都可查询。
     */
    @Test
    void acceptanceCommitsRunAndFirstEventTogether() {
        UUID runId = UUID.randomUUID();

        var accepted = acceptance.accept(createData(runId, "acceptance-key"));

        assertThat(accepted.status()).isEqualTo(AgentRunStatus.ACCEPTED);
        assertThat(runs.findById(runId)).isPresent();
        assertThat(events.findAfter(runId, 0, 10)).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.type()).isEqualTo(AgentEventType.RUN_ACCEPTED);
        });
        System.out.printf("测试证据：场景=受理短事务，runId=%s，状态=%s，首事件=RUN_ACCEPTED#1%n",
                runId, accepted.status());
    }

    /**
     * 业务目的：工具调用只保存单调序号、有限计数和脱敏参数摘要，失败终态不得残留 RUNNING 行。
     */
    @Test
    void toolCallFactsPersistSafeSummaryAndMonotonicTerminalStates() {
        UUID runId = UUID.randomUUID();
        runs.insert(createData(runId, "tool-call-key"));
        var first = toolCalls.start(runId, "knowledge_search",
                "{\"queryLength\":6,\"requestedLimit\":2}", Instant.parse("2026-07-30T01:00:00Z"));
        toolCalls.succeed(first.callId(), 2, 3, Instant.parse("2026-07-30T01:00:01Z"));
        var second = toolCalls.start(runId, "code_search",
                "{\"queryLength\":7,\"pathPrefixLength\":3}", Instant.parse("2026-07-30T01:00:02Z"));
        toolCalls.fail(second.callId(), AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED,
                Instant.parse("2026-07-30T01:00:03Z"));

        var rows = jdbcTemplate.queryForList("""
                select call_sequence, tool_name, status, argument_summary::text as summary,
                       result_count, evidence_count, error_code
                from agent_tool_call where run_id=? order by call_sequence
                """, runId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("call_sequence", 1).containsEntry("status", "SUCCEEDED")
                .containsEntry("result_count", 2).containsEntry("evidence_count", 3);
        assertThat(rows.get(1)).containsEntry("call_sequence", 2).containsEntry("status", "FAILED")
                .containsEntry("error_code", AgentErrorCode.AGENT_EVIDENCE_VERSION_CHANGED.name());
        assertThat(rows).allSatisfy(row -> assertThat(row.get("summary").toString())
                .doesNotContain("为什么", "ReviewService", "/Users/"));
        System.out.printf("测试证据：场景=工具调用台账，runId=%s，序号=1..2，终态=%s/%s，正文持久化=false%n",
                runId, rows.get(0).get("status"), rows.get(1).get("status"));
    }

    /**
     * 业务目的：运行状态只能比较更新一次，超时后到达的回答与引用必须一起被丢弃。
     */
    @Test
    void terminalCompareAndSetRejectsLateAnswerAndCitations() {
        UUID runId = UUID.randomUUID();
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
     * 业务目的：多线程追加的公开事件必须先落库再按单调序号分页读取，断线续读不得重复。
     */
    @Test
    void concurrentEventsCommitBeforeMonotonicAfterSequenceReads() throws Exception {
        UUID runId = UUID.randomUUID();
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
        System.out.printf("测试证据：场景=事件提交后续读，runId=%s，序号范围=1..20，分页=7+13%n", runId);
    }

    /**
     * 业务目的：最终回答的引用只能指向同一运行已持久化的有限证据，且可完整往返 UTC 时间和可空 Token。
     */
    @Test
    void persistedEvidenceAndCitationRoundTripWithCompletedRun() {
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
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
        UUID runId = UUID.randomUUID();
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
        UUID acceptedRunId = UUID.randomUUID();
        UUID runningRunId = UUID.randomUUID();
        UUID completedRunId = UUID.randomUUID();
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
        assertThat(events.findAfter(runningRunId, 0, 20)).singleElement()
                .extracting(event -> event.type()).isEqualTo(AgentEventType.RUN_TERMINATED);
        System.out.printf("测试证据：场景=重启恢复，accepted=%s，running=%s，terminal保持=%s，重复执行事件数=1%n",
                runs.findById(acceptedRunId).orElseThrow().status(),
                runs.findById(runningRunId).orElseThrow().status(),
                runs.findById(completedRunId).orElseThrow().status());
    }

    /**
     * 业务目的：Fake Model 产生的业务、实现和混合回答必须经过真实 PostgreSQL 证据外键与引用事务后才可发布。
     */
    @Test
    void fakeModelCompletesKnowledgeCodeAndMixedAnswersWithTrustedEvents() {
        var scenarios = List.of(AnswerBasis.BUSINESS_RULE, AnswerBasis.CURRENT_IMPLEMENTATION, AnswerBasis.MIXED);
        for (AnswerBasis basis : scenarios) {
            UUID runId = UUID.randomUUID();
            AgentEvidence knowledge = knowledgeEvidence(runId, true);
            AgentEvidence code = codeEvidence(runId, true);
            List<AgentEvidence> ledger = switch (basis) {
                case BUSINESS_RULE -> List.of(knowledge);
                case CURRENT_IMPLEMENTATION -> List.of(code);
                case MIXED -> List.of(knowledge, code);
            };
            String answer = ("可信回答-" + basis + "-").repeat(70);
            ProjectQaModelResult modelResult = new ProjectQaModelResult(
                    AgentResultType.ANSWER, basis, answer, null,
                    ledger.stream().map(AgentEvidence::id).toList());

            var snapshot = executeFake(runId, "trusted-" + basis, true, modelResult, ledger);
            assertThat(snapshot.answerBasis()).isEqualTo(basis);
            var published = events.findAfter(runId, 0, 200);
            String joined = published.stream()
                    .filter(event -> event.type() == AgentEventType.ANSWER_DELTA)
                    .map(event -> event.payload()).reduce("", String::concat);

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.resultType()).isEqualTo(AgentResultType.ANSWER);
            assertThat(snapshot.citations()).hasSize(ledger.size()).allSatisfy(citation -> {
                assertThat(citation.projectIdentifier()).isEqualTo("atlas");
                assertThat(citation.branch()).isEqualTo("main");
                assertThat(citation.sourceUpdatedAt()).isNotNull();
            });
            assertThat(joined).isEqualTo(snapshot.resultText()).isEqualTo(answer);
            assertThat(published).extracting(event -> event.sequence()).isSorted().doesNotHaveDuplicates();
            assertThat(published).extracting(event -> event.type()).containsSubsequence(
                    AgentEventType.RUN_STARTED, AgentEventType.SKILL_PINNED, AgentEventType.MODEL_STARTED,
                    AgentEventType.TOOL_STARTED, AgentEventType.SOURCE_FOUND, AgentEventType.TOOL_COMPLETED)
                    .endsWith(AgentEventType.RUN_COMPLETED);
            System.out.printf("测试证据：场景=Fake Model可信%s回答，项目=atlas，分支=main，commit=abcdef1，"
                            + "证据=%d，引用=%d，回答事件=%d，状态=%s%n",
                    basis, ledger.size(), snapshot.citations().size(),
                    published.stream().filter(event -> event.type() == AgentEventType.ANSWER_DELTA).count(),
                    snapshot.status());
        }
    }

    /**
     * 业务目的：伪造或被裁剪的引用即使来自模型最终文本，也只能落为稳定拒答，绝不能产生可信回答增量。
     */
    @Test
    void invalidModelCitationPersistsRefusalWithoutAnswerDelta() {
        UUID runId = UUID.randomUUID();
        AgentEvidence trimmed = knowledgeEvidence(runId, false);
        ProjectQaModelResult forged = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "不可信模型答案", null,
                List.of(trimmed.id(), UUID.randomUUID()));

        var snapshot = executeFake(runId, "invalid-citation", true, forged, List.of(trimmed));
        var published = events.findAfter(runId, 0, 200);

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(snapshot.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(snapshot.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
        assertThat(snapshot.resultText()).isEqualTo(ProjectQaResultValidator.REFUSAL_TEXT);
        assertThat(snapshot.citations()).isEmpty();
        assertThat(published).noneMatch(event -> event.type() == AgentEventType.ANSWER_DELTA);
        assertThat(published).filteredOn(event -> event.type() == AgentEventType.REFUSAL)
                .singleElement().extracting(event -> event.payload())
                .isEqualTo(ProjectQaResultValidator.REFUSAL_TEXT);
        System.out.printf("测试证据：场景=非法引用降级拒答，runId=%s，reason=%s，可信回答事件=0，引用=0%n",
                runId, snapshot.refusalReason());
    }

    /**
     * 业务目的：证据不足、越界、无代码快照和知识代码冲突必须持久化稳定拒答原因与有限当前范围来源。
     */
    @Test
    void refusalMatrixPersistsStableReasonsAndCurrentScopeCitations() {
        UUID insufficientRun = UUID.randomUUID();
        var insufficient = executeFake(insufficientRun, "insufficient", true,
                refusal(AgentRefusalReason.INSUFFICIENT_EVIDENCE, List.of()), List.of());
        UUID outOfScopeRun = UUID.randomUUID();
        var outOfScope = executeFake(outOfScopeRun, "out-of-scope", true,
                refusal(AgentRefusalReason.OUT_OF_SCOPE, List.of()), List.of());
        UUID noSnapshotRun = UUID.randomUUID();
        var noSnapshot = executeFake(noSnapshotRun, "no-snapshot", false,
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.CURRENT_IMPLEMENTATION,
                        "模型尝试回答实现", null, List.of()), List.of());
        UUID conflictRun = UUID.randomUUID();
        AgentEvidence knowledge = knowledgeEvidence(conflictRun, true);
        AgentEvidence code = codeEvidence(conflictRun, true);
        var conflict = executeFake(conflictRun, "conflict", true,
                new ProjectQaModelResult(AgentResultType.REFUSAL, AnswerBasis.MIXED,
                        ProjectQaResultValidator.REFUSAL_TEXT, AgentRefusalReason.SOURCE_CONFLICT,
                        List.of(knowledge.id(), code.id())), List.of(knowledge, code));

        assertThat(List.of(insufficient, outOfScope, noSnapshot, conflict))
                .allSatisfy(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
                    assertThat(snapshot.resultType()).isEqualTo(AgentResultType.REFUSAL);
                    assertThat(snapshot.resultText()).contains(ProjectQaResultValidator.REFUSAL_TEXT);
                });
        assertThat(insufficient.refusalReason()).isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(outOfScope.refusalReason()).isEqualTo(AgentRefusalReason.OUT_OF_SCOPE);
        assertThat(noSnapshot.refusalReason()).isEqualTo(AgentRefusalReason.CODE_SNAPSHOT_NOT_INDEXED);
        assertThat(noSnapshot.scope().branch()).isEqualTo("main");
        assertThat(noSnapshot.scope().snapshotId()).isNull();
        assertThat(conflict.refusalReason()).isEqualTo(AgentRefusalReason.SOURCE_CONFLICT);
        assertThat(conflict.answerBasis()).isEqualTo(AnswerBasis.MIXED);
        assertThat(conflict.citations()).extracting(citation -> citation.sourceType())
                .containsExactly(EvidenceSourceType.KNOWLEDGE, EvidenceSourceType.CODE);
        System.out.printf("测试证据：场景=可信拒答矩阵，原因=%s，无快照分支=%s，冲突引用=%d%n",
                List.of(insufficient.refusalReason(), outOfScope.refusalReason(), noSnapshot.refusalReason(),
                        conflict.refusalReason()), noSnapshot.scope().branch(), conflict.citations().size());
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

    private AgentRunCreateData createData(UUID runId, String key) {
        return createData(runId, key, Instant.parse("2026-07-30T00:00:00Z"));
    }

    private AgentRunCreateData createData(UUID runId, String key, Instant acceptedAt) {
        return new AgentRunCreateData(runId, "member", key, "c".repeat(64), "project_qa", "d".repeat(64), 12,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(SKILL_ID, "project_qa", "1.0.0", "a".repeat(64),
                        "openai-compatible", "deepseek-v4-flash", "project-qa-v1", "readonly-v1", "limits-v1"),
                acceptedAt);
    }

    private AgentRunCreateData createDataWithSnapshot(UUID runId, String key, Instant acceptedAt) {
        return new AgentRunCreateData(runId, "member", key, "c".repeat(64), "project_qa", "d".repeat(64), 12,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", SNAPSHOT_ID, "abcdef1", null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(SKILL_ID, "project_qa", "1.0.0", "a".repeat(64),
                        "openai-compatible", "deepseek-v4-flash", "project-qa-v1", "readonly-v1", "limits-v1"),
                acceptedAt);
    }

    private io.github.loredock.agent.application.AgentRunSnapshot executeFake(
            UUID runId,
            String key,
            boolean withSnapshot,
            ProjectQaModelResult modelResult,
            List<AgentEvidence> ledger
    ) {
        Instant acceptedAt = timeProvider.now();
        AgentRunCreateData data = withSnapshot
                ? createDataWithSnapshot(runId, key, acceptedAt) : createData(runId, key, acceptedAt);
        runs.insert(data);
        AgentExecutionPort fake = (request, observer) -> {
            observer.onEvent(AgentEventType.TOOL_STARTED, "knowledge_search#1");
            if (!ledger.isEmpty()) {
                evidence.saveAll(runId, ledger);
                observer.onEvent(AgentEventType.SOURCE_FOUND, "count=" + ledger.size());
            }
            observer.onEvent(AgentEventType.TOOL_COMPLETED, "knowledge_search#1:count=" + ledger.size());
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

    private ProjectQaModelResult refusal(AgentRefusalReason reason, List<UUID> citations) {
        return new ProjectQaModelResult(AgentResultType.REFUSAL, null,
                ProjectQaResultValidator.REFUSAL_TEXT, reason, citations);
    }

    private AgentEvidence knowledgeEvidence(UUID runId, boolean retained) {
        return new AgentEvidence(UUID.randomUUID(), runId, EvidenceSourceType.KNOWLEDGE, retained, 0.9,
                DOCUMENT_ID, null, "atlas", "main", null, null, "业务规则", timeProvider.now());
    }

    private AgentEvidence codeEvidence(UUID runId, boolean retained) {
        return new AgentEvidence(UUID.randomUUID(), runId, EvidenceSourceType.CODE, retained, 0.9,
                null, SNAPSHOT_ID, "atlas", "main", "abcdef1", "src/ReviewService.java", null,
                timeProvider.now());
    }

    private void seedScopeAndSkill() {
        jdbcTemplate.update("""
                insert into stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'skill-one', 'AVAILABLE', 'skill.md', 'text/markdown', 10, ?, now(), now(), 'test', 'test')
                """, UUID.randomUUID().toString(), "a".repeat(64));
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'atlas', 'Atlas', '', '', 'ENABLED', now(), now(), 'test', 'test')
                """, PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?::uuid, ?::uuid, 'main', now(), now(), 'test', 'test')
                """, BRANCH_ID.toString(), PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'public-code-fixture', 'AVAILABLE', 'public-code.zip', 'application/zip', 10,
                    ?, now(), now(), 'test', 'test')
                """, UUID.randomUUID().toString(), "b".repeat(64));
        jdbcTemplate.update("""
                insert into code_snapshot(id, project_id, branch_id, commit_hash, input_object_key, status,
                    indexed_file_count, ignored_file_count, indexed_at,
                    created_at, updated_at, created_by, updated_by)
                values (?::uuid, ?::uuid, ?::uuid, 'abcdef1', 'public-code-fixture', 'ACTIVE', 3, 0, now(),
                    now(), now(), 'test', 'test')
                """, SNAPSHOT_ID.toString(), PROJECT_ID.toString(), BRANCH_ID.toString());
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type, project_id,
                    branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'MARKDOWN', '业务规则', '公开模拟正文', '/', 'PROJECT', ?::uuid,
                    null, 'MANUAL', 'PUBLISHED', 1, now(), 'test', now(), now(), 'test', 'test')
                """, DOCUMENT_ID.toString(), PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into agent_skill_version(id, skill_name, skill_version, content_hash, object_key,
                    output_schema_version, status, created_at)
                values (?::uuid, 'project_qa', '1.0.0', ?, 'skill-one', 'project-qa-v1', 'ENABLED', now())
                """, SKILL_ID.toString(), "a".repeat(64));
    }
}
