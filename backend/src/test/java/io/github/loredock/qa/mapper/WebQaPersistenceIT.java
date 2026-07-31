package io.github.loredock.qa.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentService;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.service.AgentRunService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.model.command.CreateWebQaQuestionCommand;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import io.github.loredock.qa.service.CreateWebQaQuestionService;
import io.github.loredock.qa.service.WebQaMessageDataService;
import io.github.loredock.qa.service.WebQaQuestionDataService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
class WebQaPersistenceIT {
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Long PROJECT_ID = 841618992519970818L;
    private static final Long BRANCH_ID = 841618992519970819L;
    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_web_qa_persistence_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private ProjectService projects;
    @Autowired private AgentRunService runs;
    @Autowired private WebQaQuestionDataService questions;
    @Autowired private WebQaMessageDataService messages;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

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
        for (String table : List.of(
                "knowledge_gap_feedback_citation", "knowledge_gap_feedback", "web_qa_message", "web_qa_question",
                "agent_evidence", "agent_run_event", "agent_run",
                "knowledge_document", "code_snapshot", "project_branch", "project_space",
                "stored_object")) {
            jdbcTemplate.update("delete from " + table);
        }
        seedScopeAndSkill();
    }

    /**
     * 业务目的：问答、用户消息、运行和首事件必须在同一提交后共同可见，防止 Web 历史出现孤立记录。
     */
    @Test
    void creationCommitsQuestionMessageRunAndFirstEventAtomically() {
        CreateWebQaQuestionService service = service(startUseCase(null, null), messages);

        WebQaQuestionSnapshot created = transaction().execute(status -> service.create(command("atomic-key")));

        assertThat(created).isNotNull();
        assertThat(count("web_qa_question")).isEqualTo(1);
        assertThat(count("web_qa_message")).isEqualTo(1);
        assertThat(count("agent_run")).isEqualTo(1);
        assertThat(count("agent_run_event")).isEqualTo(1);
        System.out.printf("测试证据：场景=问答原子提交，questionId=%s，runId=%s，问答/消息/运行/首事件=1/1/1/1%n",
                created.question().id(), created.run().runId());
    }

    /**
     * 业务目的：两个并发相同请求只能提交一个问答、一个用户消息和一个运行，模型调度入口最多得到一个新受理。
     */
    @Test
    void concurrentIdenticalCreationReturnsOneQuestionAndRun() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CreateWebQaQuestionService service = service(startUseCase(ready, start), messages);
        WebQaQuestionSnapshot first;
        WebQaQuestionSnapshot second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> transaction().execute(status -> service.create(command("same-key"))));
            var secondFuture = executor.submit(() -> transaction().execute(status -> service.create(command("same-key"))));
            ready.await();
            start.countDown();
            first = firstFuture.get();
            second = secondFuture.get();
        }

        assertThat(second.question().id()).isEqualTo(first.question().id());
        assertThat(second.run().runId()).isEqualTo(first.run().runId());
        assertThat(count("web_qa_question")).isEqualTo(1);
        assertThat(count("web_qa_message")).isEqualTo(1);
        assertThat(count("agent_run")).isEqualTo(1);
        assertThat(count("agent_run_event")).isEqualTo(1);
        System.out.printf("测试证据：场景=并发问答幂等，questionId=%s，runId=%s，数据库行=1/1/1/1%n",
                first.question().id(), first.run().runId());
    }

    /**
     * 业务目的：用户消息写入失败必须回滚同事务内的运行、首事件和问答，防止后台执行用户无法恢复的孤立任务。
     */
    @Test
    void messageFailureRollsBackRunEventAndQuestion() {
        WebQaMessageDataService failingMessages = mock(WebQaMessageDataService.class);
        when(failingMessages.insertIfAbsent(any())).thenThrow(
                new IllegalStateException("simulated message persistence failure"));
        CreateWebQaQuestionService service = service(startUseCase(null, null), failingMessages);

        assertThatThrownBy(() -> transaction().execute(status -> service.create(command("rollback-key"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("web_qa_question")).isZero();
        assertThat(count("web_qa_message")).isZero();
        assertThat(count("agent_run")).isZero();
        assertThat(count("agent_run_event")).isZero();
        System.out.println("测试证据：场景=消息失败回滚，问答/消息/运行/首事件=0/0/0/0");
    }

    private CreateWebQaQuestionService service(
            AgentService agents,
            WebQaMessageDataService messageRepository
    ) {
        when(agents.get(any(), any())).thenAnswer(invocation -> runs.findById(invocation.getArgument(0))
                .filter(value -> value.operatorId().equals(invocation.getArgument(1)))
                .map(io.github.loredock.testsupport.AgentApiFixtures::run).orElseThrow());
        return new CreateWebQaQuestionService(
                projects, agents, questions, messageRepository, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private AgentService startUseCase(CountDownLatch ready, CountDownLatch start) {
        AgentService service = mock(AgentService.class);
        when(service.start(any())).thenAnswer(invocation -> {
            var command = (AgentService.StartRequest) invocation.getArgument(0);
            awaitBarrier(ready, start);
            ProjectScope project = projects.resolveEnabledScope(command.projectIdentifier(), command.branch());
            AgentScopeSnapshot scope = new AgentScopeSnapshot(
                    project.projectId(), project.projectIdentifier(), project.branchId(), project.branchName(),
                    null, null, null, List.of("GLOBAL", "PROJECT", "BRANCH"));
            AgentVersionSnapshot versions = new AgentVersionSnapshot(
                    "project_qa", "fake-model", "project-qa-v1");
            AgentRunCreateData data = new AgentRunCreateData(
                    8000000000000000180L, command.operatorId(), command.idempotencyKey(),
                    hash(project.projectIdentifier() + "\n" + project.branchName() + "\n" + command.question()),
                    "project_qa", hash(command.question()),
                    command.question().codePointCount(0, command.question().length()), scope, versions, NOW);
            return io.github.loredock.testsupport.AgentApiFixtures.run(runs.accept(data).snapshot());
        });
        return service;
    }

    private void awaitBarrier(CountDownLatch ready, CountDownLatch start) {
        if (ready == null || start == null) {
            return;
        }
        try {
            ready.countDown();
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test barrier interrupted", exception);
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private CreateWebQaQuestionCommand command(String key) {
        return CreateWebQaQuestionCommand.of("member", "MEMBER", key, "atlas", null, "为什么这样设计？");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
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
    }
}
