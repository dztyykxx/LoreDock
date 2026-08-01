package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.converter.ProjectQaResultConverter;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentExecutionResult;
import io.github.loredock.agent.model.result.AgentExecutionUsage;
import io.github.loredock.agent.model.result.ProjectQaModelResult;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.service.AgentEventService;
import io.github.loredock.agent.service.AgentEvidenceService;
import io.github.loredock.agent.service.AgentRunService;
import io.github.loredock.agent.service.ProjectQaRunTaskExecutor;
import io.github.loredock.auth.api.AuthService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.api.QaQuestion;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.config.WebQaSseProperties;
import io.github.loredock.qa.model.request.WebQaSseStreamRequest;
import io.github.loredock.qa.model.snapshot.WebQaSsePublicEvent;
import io.github.loredock.qa.scheduler.BoundedWebQaSseExecutor;
import io.github.loredock.qa.service.impl.WebQaSseSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class WebQaSsePersistenceIT {
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Long PROJECT_ID = 6649233113080659970L;
    private static final Long BRANCH_ID = 6649233113080659971L;
    private static final Long DOCUMENT_ID = 6649233113080659973L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_web_qa_sse_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private ProjectService projects;
    @Autowired private AgentRunService runs;
    @Autowired private AgentService agents;
    @Autowired private AgentEventService eventRepository;
    @Autowired private AgentEvidenceService evidence;
    @Autowired private WebQaQuestionDataService questions;
    @Autowired private WebQaConversationDataService conversations;
    @Autowired private WebQaMessageDataService messages;
    @Autowired private DefaultWebQaAssistantMessageMaterializer materializer;
    @Autowired private QaServiceImpl questionQueries;
    @Autowired private Clock timeProvider;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    private io.github.loredock.agent.model.snapshot.AgentRunSnapshot acceptedRun;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.web.accounts[0].username", () -> "member");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "成员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "MEMBER");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "管理员");
        registry.add("loredock.identity.web.accounts[1].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[1].password-hash", () -> BCRYPT_HASH);
    }

    @BeforeEach
    void resetFacts() {
        for (String table : List.of(
                "knowledge_gap_feedback_citation", "knowledge_gap_feedback", "web_qa_message", "web_qa_question",
                "web_qa_conversation",
                "agent_evidence", "agent_run_event", "agent_run",
                "knowledge_document", "code_snapshot", "project_branch", "project_space",
                "stored_object")) {
            jdbcTemplate.update("delete from " + table);
        }
        seedScopeAndSkill();
    }

    /**
     * 业务目的：SSE 必须只从 PostgreSQL 已提交事件恢复，并在断线续读后与终态详情和引用一致。
     */
    @Test
    void persistedEventsResumeAfterDisconnectAndConvergeToTerminalDetail() throws IOException {
        long startedNanos = System.nanoTime();
        AgentService starts = mock(AgentService.class);
        when(starts.start(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                acceptRun(invocation.getArgument(0)));
        QaServiceImpl creation = new QaServiceImpl(
                projects, starts, conversations, questions, messages, materializer, timeProvider);
        QaQuestion created = new TransactionTemplate(transactionManager).execute(
                status -> creation.create(new QaService.CreateRequest(
                        "member", "MEMBER", "sse-it-key", "atlas", "main", "为什么采用范围隔离？")));
        assertThat(created).isNotNull();

        Long runId = created.runId();
        executeScriptedModel(created);

        AuthService sessions = alwaysValidSession();
        WebQaSseService streams = new WebQaSseService(
                new WebQaSseProperties(Duration.ofSeconds(5), Duration.ofMinutes(1),
                        2, 1, 1, 0, Duration.ofSeconds(1)),
                mock(BoundedWebQaSseExecutor.class), timeProvider, questionQueries, agents,
                sessions, materializer);
        WebQaSseStreamRequest request = new WebQaSseStreamRequest(
                "member", "atlas", created.questionId(), runId, 0, sessions.capture());

        RecordingSink firstConnection = new RecordingSink(2);
        streams.stream(request, firstConnection, timeProvider.instant());
        assertThat(firstConnection.events).hasSize(2);
        assertThat(firstConnection.events).extracting(event -> event.data().sequence())
                .containsExactly(1L, 2L);

        // 模拟浏览器在第二条已落库事件后断线；新连接只能用该序号继续。
        long firstCursor = firstConnection.events.getLast().data().sequence();
        RecordingSink resumedConnection = new RecordingSink(Integer.MAX_VALUE);
        streams.stream(new WebQaSseStreamRequest(
                        "member", "atlas", created.questionId(), runId, firstCursor, sessions.capture()),
                resumedConnection, timeProvider.instant());
        QaQuestion detail = questionQueries.detail(
                new QaService.DetailQuery("member", "atlas", created.questionId()));

        assertThat(resumedConnection.events).isNotEmpty();
        assertThat(resumedConnection.events).allMatch(event -> event.data().sequence() > firstCursor);
        assertThat(resumedConnection.events).extracting(event -> event.data().sequence()).isSorted();
        assertThat(resumedConnection.events.getLast().name()).isEqualTo("run.completed");
        assertThat(resumedConnection.closed).isTrue();
        assertThat(detail.resultType()).isEqualTo(QaQuestion.ResultType.ANSWER);
        assertThat(detail.answerBasis()).isEqualTo(QaQuestion.AnswerBasis.BUSINESS_RULE);
        assertThat(detail.citations()).hasSize(1);
        assertThat(detail.messages()).hasSize(2);

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        List<String> eventTypes = new ArrayList<>();
        firstConnection.events.forEach(event -> eventTypes.add(event.name()));
        resumedConnection.events.forEach(event -> eventTypes.add(event.name()));
        System.out.printf("测试证据：场景=真实PostgreSQL SSE断线续读，首序号=%d，末序号=%d，"
                        + "事件类型=%s，终态=%s，引用数=%d，耗时毫秒=%d%n",
                firstConnection.events.getFirst().data().sequence(), resumedConnection.events.getLast().data().sequence(), eventTypes,
                detail.status(), detail.citations().size(), elapsedMillis);
    }

    private AgentRun acceptRun(
            AgentService.StartRequest command
    ) {
        ProjectScope project = projects.resolveEnabledScope(command.projectIdentifier(), command.branch());
        Instant acceptedAt = timeProvider.instant();
        AgentRunCreateData data = new AgentRunCreateData(
                8000000000000000163L, command.operatorId(), command.idempotencyKey(),
                hash(project.projectIdentifier() + "\n" + project.branchName() + "\n" + command.question()),
                "project_qa", hash(command.question()),
                command.question().codePointCount(0, command.question().length()),
                new AgentScopeSnapshot(
                        project.projectId(), project.projectIdentifier(), project.branchId(), project.branchName(),
                        null, null, null, List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "scripted-model", "project-qa-v1"),
                acceptedAt);
        acceptedRun = runs.accept(data).snapshot();
        return io.github.loredock.testsupport.AgentApiFixtures.run(acceptedRun);
    }

    private void executeScriptedModel(QaQuestion created) {
        Long runId = created.runId();
        Long evidenceId = 8000000000000000164L;
        AgentEvidence source = new AgentEvidence(
                evidenceId, runId, EvidenceSourceType.KNOWLEDGE, true, 0.95,
                DOCUMENT_ID, null, "atlas", "main", null, null, "范围隔离规则", timeProvider.instant());
        var fakeModel = (io.github.loredock.agent.service.AgentRuntime) request -> {
            evidence.saveAll(runId, List.of(source));
            return new AgentExecutionResult(
                    new ProjectQaModelResult(
                            AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                            "范围隔离用于防止跨项目召回。", null, List.of(evidenceId)),
                    List.of(source), new AgentExecutionUsage(3, 1, 1, 0, 20L, 10L, 25));
        };
        new ProjectQaRunTaskExecutor(
                java.util.Optional.of(fakeModel), runs, eventRepository,
                new ProjectQaResultConverter(), timeProvider)
                .execute(new AgentExecutionRequest(
                        runId, "为什么采用范围隔离？", "skill", "schema",
                        acceptedRun.scope(), acceptedRun.versions(),
                        new AgentRuntimeLimits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200),
                        timeProvider.instant().plusSeconds(30)));
    }

    private AuthService alwaysValidSession() {
        AuthService sessions = mock(AuthService.class);
        AuthService.SessionLease lease = mock(AuthService.SessionLease.class);
        when(sessions.capture()).thenReturn(lease);
        when(sessions.isValid(lease, "member")).thenReturn(true);
        return sessions;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
                values (?, 'atlas', 'Atlas', '', 'Java', 'ENABLED', now(), now(), 'test', 'test')
                """, PROJECT_ID);
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', now(), now(), 'test', 'test')
                """, BRANCH_ID, PROJECT_ID);
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type, project_id,
                    branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'MARKDOWN', '范围隔离规则', '公开模拟正文', '/', 'PROJECT', ?,
                    null, 'MANUAL', 'PUBLISHED', 1, now(), 'test', now(), now(), 'test', 'test')
                """, DOCUMENT_ID, PROJECT_ID);
    }

    private static final class RecordingSink implements WebQaSseSink {
        private final List<WebQaSsePublicEvent> events = new ArrayList<>();
        private final int closeAfter;
        private boolean closed;

        private RecordingSink(int closeAfter) {
            this.closeAfter = closeAfter;
        }

        @Override
        public void send(WebQaSsePublicEvent event) {
            events.add(event);
            if (events.size() >= closeAfter) {
                closed = true;
            }
        }

        @Override
        public void heartbeat() {
        }

        @Override
        public void complete() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}
