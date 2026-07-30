package io.github.loredock.knowledgegap.infrastructure.persistence;

import io.github.loredock.knowledgegap.application.AdminKnowledgeGapUseCase;
import io.github.loredock.knowledgegap.application.CreateKnowledgeGapCommand;
import io.github.loredock.knowledgegap.application.CreateKnowledgeGapUseCase;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackPage;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackRecord;
import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackRepository;
import io.github.loredock.knowledgegap.application.KnowledgeGapFilter;
import io.github.loredock.knowledgegap.application.QueryKnowledgeGapsCommand;
import io.github.loredock.knowledgegap.application.UpdateKnowledgeGapStatusCommand;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeGapPersistenceIT {
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final UUID PROJECT_ID = UUID.fromString("79000000-0000-0000-0000-000000000001");
    private static final UUID BRANCH_ID = UUID.fromString("79000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-07-30T14:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_knowledge_gap_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private CreateKnowledgeGapUseCase creates;
    @Autowired private AdminKnowledgeGapUseCase manages;
    @Autowired private KnowledgeGapFeedbackRepository repository;
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
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?::uuid, 'atlas', 'Atlas', '', 'Java', 'ENABLED', now(), now(), 'admin', 'admin')
                """, PROJECT_ID.toString());
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?::uuid, ?::uuid, 'main', now(), now(), 'admin', 'admin')
                """, BRANCH_ID.toString(), PROJECT_ID.toString());
    }

    /**
     * 业务目的：真实 PostgreSQL 幂等创建只能保留一条反馈，并且绝不写知识、索引或 Agent 运行事实。
     */
    @Test
    void manualCreationIsIdempotentWithoutKnowledgeIndexOrAgentSideEffects() {
        CreateKnowledgeGapCommand command = CreateKnowledgeGapCommand.of(
                "member", "db-retry-key", "atlas", null, KnowledgeGapType.OUTDATED_KNOWLEDGE,
                null, "哪些业务规则已经过期？", "等待产品确认");

        var first = creates.create(command);
        var retried = creates.create(command);

        assertThat(retried.feedback().id()).isEqualTo(first.feedback().id());
        assertThat(count("knowledge_gap_feedback")).isEqualTo(1);
        assertThat(count("knowledge_gap_feedback_citation")).isZero();
        assertThat(count("knowledge_document")).isZero();
        assertThat(count("knowledge_index_generation")).isZero();
        assertThat(count("agent_run")).isZero();
        System.out.printf("测试证据：场景=真实PostgreSQL反馈幂等，feedbackId=%s，反馈/引用=1/0，"
                        + "知识/索引/Agent写入=0/0/0%n", first.feedback().id());
    }

    /**
     * 业务目的：两个管理员并发确认同一 OPEN 反馈时都收敛到 ACKNOWLEDGED，数据库不发生倒退或重复行。
     */
    @Test
    void concurrentAdministratorsConvergeThroughConditionalStatusUpdate() throws Exception {
        KnowledgeGapFeedbackRecord open = record(UUID.fromString("79000000-0000-0000-0000-000000000010"));
        assertThat(repository.insertIfAbsent(open)).isTrue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> updateAfterBarrier("admin", open.id(), ready, start));
            var second = executor.submit(() -> updateAfterBarrier("admin-two", open.id(), ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().feedback().status()).isEqualTo(KnowledgeGapStatus.ACKNOWLEDGED);
            assertThat(second.get().feedback().status()).isEqualTo(KnowledgeGapStatus.ACKNOWLEDGED);
        }

        var closed = manages.updateStatus(new UpdateKnowledgeGapStatusCommand(
                "admin", open.id(), KnowledgeGapStatus.CLOSED));
        KnowledgeGapFeedbackPage page = manages.list(new QueryKnowledgeGapsCommand(
                new KnowledgeGapFilter("atlas", "main", KnowledgeGapType.NO_ANSWER, KnowledgeGapStatus.CLOSED),
                null, 20));
        assertThat(page.items()).hasSize(1);
        assertThat(closed.feedback().updatedAt()).isAfterOrEqualTo(open.updatedAt());
        assertThat(count("knowledge_gap_feedback")).isEqualTo(1);
        System.out.printf("测试证据：场景=真实PostgreSQL并发状态更新，feedbackId=%s，终态=%s，"
                        + "审计操作者=%s，过滤命中=%d%n",
                open.id(), closed.feedback().status(), closed.feedback().updatedBy(), page.items().size());
    }

    private io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackSnapshot updateAfterBarrier(
            String actor, UUID feedbackId, CountDownLatch ready, CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return manages.updateStatus(new UpdateKnowledgeGapStatusCommand(
                actor, feedbackId, KnowledgeGapStatus.ACKNOWLEDGED));
    }

    private KnowledgeGapFeedbackRecord record(UUID id) {
        return new KnowledgeGapFeedbackRecord(
                id, "member", "state-key", "a".repeat(64), PROJECT_ID, "atlas", BRANCH_ID, "main",
                null, null, KnowledgeGapType.NO_ANSWER, KnowledgeGapStatus.OPEN,
                "为什么没有答案？", null, null, null, null, NOW, NOW, "member", "member");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
