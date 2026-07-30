package io.github.loredock.qa.infrastructure.persistence;

import io.github.loredock.agent.application.AgentRunAcceptanceService;
import io.github.loredock.agent.application.AgentRunCreateData;
import io.github.loredock.agent.application.AgentRunQueryUseCase;
import io.github.loredock.agent.application.AgentRunRepository;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.application.StartProjectQaRunUseCase;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.application.CreateWebQaQuestionCommand;
import io.github.loredock.qa.application.CreateWebQaQuestionService;
import io.github.loredock.qa.application.WebQaMessageRecord;
import io.github.loredock.qa.application.WebQaMessageRepository;
import io.github.loredock.qa.application.WebQaQuestionRecord;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class WebQaPersistenceIT {
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final UUID PROJECT_ID = UUID.fromString("74000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("74000000-0000-0000-0000-000000000002");
    private static final UUID SKILL_ID = UUID.fromString("74000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_web_qa_persistence_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private ProjectQueryUseCase projects;
    @Autowired private AgentRunAcceptanceService acceptance;
    @Autowired private AgentRunRepository runs;
    @Autowired private WebQaQuestionRepository questions;
    @Autowired private WebQaMessageRepository messages;
    @Autowired private PlatformTransactionManager transactionManager;
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
        WebQaMessageRepository failingMessages = new WebQaMessageRepository() {
            @Override
            public boolean insertIfAbsent(WebQaMessageRecord message) {
                throw new IllegalStateException("simulated message persistence failure");
            }

            @Override
            public List<WebQaMessageRecord> findByQuestionId(UUID questionId) {
                return messages.findByQuestionId(questionId);
            }
        };
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
            StartProjectQaRunUseCase starts,
            WebQaMessageRepository messageRepository
    ) {
        AgentRunQueryUseCase runQueries = (runId, operatorId) -> runs.findById(runId)
                .filter(value -> value.operatorId().equals(operatorId))
                .orElseThrow();
        return new CreateWebQaQuestionService(
                projects, starts, runQueries, questions, messageRepository, () -> NOW);
    }

    private StartProjectQaRunUseCase startUseCase(CountDownLatch ready, CountDownLatch start) {
        return command -> {
            awaitBarrier(ready, start);
            ProjectDetailView project = projects.getEnabledProject(command.projectIdentifier(), command.branch());
            AgentScopeSnapshot scope = new AgentScopeSnapshot(
                    project.id(), project.identifier(), BRANCH_ID, project.selectedBranch(),
                    null, null, null, List.of("GLOBAL", "PROJECT", "BRANCH"));
            AgentVersionSnapshot versions = new AgentVersionSnapshot(
                    SKILL_ID, "project_qa", "1.0.0", "a".repeat(64),
                    "fake", "fake-model", "project-qa-v1", "readonly-v1", "limits-v1");
            AgentRunCreateData data = new AgentRunCreateData(
                    UUID.randomUUID(), command.operatorId(), command.idempotencyKey(),
                    hash(project.identifier() + "\n" + project.selectedBranch() + "\n" + command.question()),
                    "project_qa", hash(command.question()),
                    command.question().codePointCount(0, command.question().length()), scope, versions, NOW);
            return acceptance.accept(data).snapshot();
        };
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
                insert into agent_skill_version(id, skill_name, skill_version, content_hash, object_key,
                    output_schema_version, status, created_at)
                values (?::uuid, 'project_qa', '1.0.0', ?, 'skill-one', 'project-qa-v1', 'ENABLED', now())
                """, SKILL_ID.toString(), "a".repeat(64));
    }
}
