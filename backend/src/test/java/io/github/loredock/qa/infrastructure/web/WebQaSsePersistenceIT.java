package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.application.AgentEventQueryUseCase;
import io.github.loredock.agent.application.AgentEventRepository;
import io.github.loredock.agent.application.AgentEvidenceRepository;
import io.github.loredock.agent.application.AgentExecutionRequest;
import io.github.loredock.agent.application.AgentExecutionResult;
import io.github.loredock.agent.application.AgentExecutionUsage;
import io.github.loredock.agent.application.AgentRunAcceptanceService;
import io.github.loredock.agent.application.AgentRunCreateData;
import io.github.loredock.agent.application.AgentRunQueryUseCase;
import io.github.loredock.agent.application.AgentRunRepository;
import io.github.loredock.agent.application.AgentRuntimeLimits;
import io.github.loredock.agent.application.ProjectQaRunTaskExecutor;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentEvidence;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.agent.domain.AnswerBasis;
import io.github.loredock.agent.domain.EvidenceSourceType;
import io.github.loredock.agent.domain.ProjectQaModelResult;
import io.github.loredock.agent.domain.ProjectQaResultValidator;
import io.github.loredock.identity.application.WebSessionContinuityPort;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.application.CreateWebQaQuestionCommand;
import io.github.loredock.qa.application.CreateWebQaQuestionService;
import io.github.loredock.qa.application.QueryWebQaDetailCommand;
import io.github.loredock.qa.application.QueryWebQaQuestionService;
import io.github.loredock.qa.application.WebQaAssistantMessageMaterializer;
import io.github.loredock.qa.application.WebQaMessageRepository;
import io.github.loredock.qa.application.WebQaQuestionRepository;
import io.github.loredock.qa.application.WebQaQuestionSnapshot;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class WebQaSsePersistenceIT {
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final UUID PROJECT_ID = UUID.fromString("76000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("76000000-0000-0000-0000-000000000002");
    private static final UUID SKILL_ID = UUID.fromString("76000000-0000-0000-0000-000000000003");
    private static final UUID DOCUMENT_ID = UUID.fromString("76000000-0000-0000-0000-000000000004");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_web_qa_sse_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private ProjectQueryUseCase projects;
    @Autowired private AgentRunAcceptanceService acceptance;
    @Autowired private AgentRunRepository runs;
    @Autowired private AgentRunQueryUseCase runQueries;
    @Autowired private AgentEventRepository eventRepository;
    @Autowired private AgentEventQueryUseCase eventQueries;
    @Autowired private AgentEvidenceRepository evidence;
    @Autowired private WebQaQuestionRepository questions;
    @Autowired private WebQaMessageRepository messages;
    @Autowired private WebQaAssistantMessageMaterializer materializer;
    @Autowired private QueryWebQaQuestionService questionQueries;
    @Autowired private TimeProvider timeProvider;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.mcp.token-sha256", () -> "a".repeat(64));
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
                "agent_citation", "agent_evidence", "agent_tool_call", "agent_run_event", "agent_run",
                "agent_skill_version", "knowledge_document", "code_snapshot", "project_branch", "project_space",
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
        CreateWebQaQuestionService creation = new CreateWebQaQuestionService(
                projects, this::acceptRun, runQueries, questions, messages, timeProvider);
        WebQaQuestionSnapshot created = new TransactionTemplate(transactionManager).execute(
                status -> creation.create(CreateWebQaQuestionCommand.of(
                        "member", "MEMBER", "sse-it-key", "atlas", "main", "为什么采用范围隔离？")));
        assertThat(created).isNotNull();

        UUID runId = created.run().runId();
        executeScriptedModel(created);

        WebSessionContinuityPort sessions = alwaysValidSession();
        WebQaSsePoller poller = new WebQaSsePoller(
                questionQueries, eventQueries, sessions, materializer, 5, Duration.ofSeconds(15));
        WebQaSseStreamRequest request = new WebQaSseStreamRequest(
                "member", "atlas", created.question().id(), runId, 0, sessions.capture());

        RecordingSink firstConnection = new RecordingSink();
        WebQaSsePollResult first = poller.poll(
                request, 0, timeProvider.now(), timeProvider.now(), firstConnection);
        assertThat(first.sentCount()).isEqualTo(5);
        assertThat(firstConnection.closed).isFalse();
        assertThat(firstConnection.events).extracting(event -> event.data().sequence())
                .containsExactly(1L, 2L, 3L, 4L, 5L);

        // 模拟浏览器在第五条已落库事件后断线；新连接只能用该序号继续，不能重发旧正文。
        RecordingSink resumedConnection = new RecordingSink();
        WebQaSsePollResult resumed = poller.poll(
                request, first.cursor(), timeProvider.now(), timeProvider.now(), resumedConnection);
        WebQaQuestionSnapshot detail = questionQueries.detail(
                new QueryWebQaDetailCommand("member", "atlas", created.question().id()));

        assertThat(resumedConnection.events).isNotEmpty();
        assertThat(resumedConnection.events).allMatch(event -> event.data().sequence() > first.cursor());
        assertThat(resumedConnection.events).extracting(event -> event.data().sequence()).isSorted();
        assertThat(resumedConnection.events.getLast().name()).isEqualTo("run.completed");
        assertThat(resumed.closed()).isTrue();
        assertThat(detail.run().resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(detail.run().answerBasis()).isEqualTo(AnswerBasis.BUSINESS_RULE);
        assertThat(detail.run().citations()).hasSize(1);
        assertThat(detail.messages()).hasSize(2);

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        List<String> eventTypes = new ArrayList<>();
        firstConnection.events.forEach(event -> eventTypes.add(event.name()));
        resumedConnection.events.forEach(event -> eventTypes.add(event.name()));
        System.out.printf("测试证据：场景=真实PostgreSQL SSE断线续读，首序号=%d，末序号=%d，"
                        + "事件类型=%s，终态=%s，引用数=%d，耗时毫秒=%d%n",
                firstConnection.events.getFirst().data().sequence(), resumed.cursor(), eventTypes,
                detail.run().status(), detail.run().citations().size(), elapsedMillis);
    }

    private io.github.loredock.agent.application.AgentRunSnapshot acceptRun(
            io.github.loredock.agent.application.StartProjectQaRunCommand command
    ) {
        ProjectDetailView project = projects.getEnabledProject(command.projectIdentifier(), command.branch());
        Instant acceptedAt = timeProvider.now();
        AgentRunCreateData data = new AgentRunCreateData(
                UUID.randomUUID(), command.operatorId(), command.idempotencyKey(),
                hash(project.identifier() + "\n" + project.selectedBranch() + "\n" + command.question()),
                "project_qa", hash(command.question()),
                command.question().codePointCount(0, command.question().length()),
                new AgentScopeSnapshot(
                        project.id(), project.identifier(), BRANCH_ID, project.selectedBranch(),
                        null, null, null, List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(
                        SKILL_ID, "project_qa", "1.0.0", "a".repeat(64),
                        "fake", "scripted-model", "project-qa-v1", "readonly-v1", "limits-v1"),
                acceptedAt);
        return acceptance.accept(data).snapshot();
    }

    private void executeScriptedModel(WebQaQuestionSnapshot created) {
        UUID runId = created.run().runId();
        UUID evidenceId = UUID.randomUUID();
        AgentEvidence source = new AgentEvidence(
                evidenceId, runId, EvidenceSourceType.KNOWLEDGE, true, 0.95,
                DOCUMENT_ID, null, "atlas", "main", null, null, "范围隔离规则", timeProvider.now());
        var fakeModel = (io.github.loredock.agent.application.AgentExecutionPort) (request, observer) -> {
            observer.onEvent(AgentEventType.TOOL_STARTED, "knowledge_search#1");
            evidence.saveAll(runId, List.of(source));
            observer.onEvent(AgentEventType.SOURCE_FOUND, "knowledge_search count=1");
            observer.onEvent(AgentEventType.TOOL_COMPLETED, "knowledge_search count=1");
            return new AgentExecutionResult(
                    new ProjectQaModelResult(
                            AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                            "范围隔离用于防止跨项目召回。", null, List.of(evidenceId)),
                    List.of(source), new AgentExecutionUsage(3, 1, 1, 0, 20L, 10L, 25));
        };
        new ProjectQaRunTaskExecutor(
                java.util.Optional.of(fakeModel), runs, eventRepository,
                new ProjectQaResultValidator(), timeProvider)
                .execute(new AgentExecutionRequest(
                        runId, "为什么采用范围隔离？", "skill", "schema",
                        created.run().scope(), created.run().versions(),
                        new AgentRuntimeLimits(8, 8, Duration.ofSeconds(30), 10, 2000, 24000, 8000, 200),
                        timeProvider.now().plusSeconds(30)));
    }

    private WebSessionContinuityPort alwaysValidSession() {
        return new WebSessionContinuityPort() {
            private final Lease lease = new Lease() {
            };

            @Override
            public Lease capture() {
                return lease;
            }

            @Override
            public boolean isValid(Lease candidate, String expectedUsername) {
                return candidate == lease && "member".equals(expectedUsername);
            }
        };
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
                insert into stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'skill-one', 'AVAILABLE', 'skill.md', 'text/markdown', 10, ?, now(), now(), 'test', 'test')
                """, UUID.randomUUID().toString(), "a".repeat(64));
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'atlas', 'Atlas', '', 'Java', 'ENABLED', now(), now(), 'test', 'test')
                """, PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?::uuid, ?::uuid, 'main', now(), now(), 'test', 'test')
                """, BRANCH_ID.toString(), PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type, project_id,
                    branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'MARKDOWN', '范围隔离规则', '公开模拟正文', '/', 'PROJECT', ?::uuid,
                    null, 'MANUAL', 'PUBLISHED', 1, now(), 'test', now(), now(), 'test', 'test')
                """, DOCUMENT_ID.toString(), PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into agent_skill_version(id, skill_name, skill_version, content_hash, object_key,
                    output_schema_version, status, created_at)
                values (?::uuid, 'project_qa', '1.0.0', ?, 'skill-one', 'project-qa-v1', 'ENABLED', now())
                """, SKILL_ID.toString(), "a".repeat(64));
    }

    private static final class RecordingSink implements WebQaSseSink {
        private final List<WebQaSsePublicEvent> events = new ArrayList<>();
        private boolean closed;

        @Override
        public void send(WebQaSsePublicEvent event) {
            events.add(event);
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
