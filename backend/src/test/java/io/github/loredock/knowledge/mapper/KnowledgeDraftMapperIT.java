package io.github.loredock.knowledge.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

/**
 * 工作草稿与会话绑定 SQL 的 PostgreSQL 集成契约测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class KnowledgeDraftMapperIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-08-12T02:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_draft_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private KnowledgeDraftMapper drafts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void resetDatabase() {
        // conversation.current_draft_id 与 knowledge_draft.conversation_id 互相外键引用，先解绑再删除。
        jdbcTemplate.update("update knowledge_task_conversation set current_draft_id = null");
        jdbcTemplate.update("delete from knowledge_draft");
        jdbcTemplate.update("delete from knowledge_task_conversation");
        jdbcTemplate.update("delete from agent_run");
    }

    /**
     * 业务目的：GLOBAL 通用知识整理会话的 project_id 为 NULL，草稿绑定必须匹配全局会话并回写
     * current_draft_id；防止参数为 NULL 时 PostgreSQL 无法推断参数类型导致绑定失败。
     */
    @Test
    void attachesDraftToGlobalConversationWithNullProjectId() {
        java.sql.Timestamp now = java.sql.Timestamp.from(NOW);
        String hash = "ab".repeat(32);
        jdbcTemplate.update("""
                insert into knowledge_task_conversation
                    (operator_id, idempotency_key, request_hash, project_id, project_identifier,
                     trigger_type, trigger_reason, target_skill, goal, created_at, updated_at)
                values (?, ?, ?, null, 'GLOBAL', 'MANUAL', ?, 'knowledge-curator', ?, ?, ?)
                """, "admin", "it-global-conversation", hash,
                "集成测试：全局会话绑定工作草稿", "整理通用业务知识", now, now);
        Long conversationId = jdbcTemplate.queryForObject(
                "select id from knowledge_task_conversation where operator_id = ? and idempotency_key = ?",
                Long.class, "admin", "it-global-conversation");
        // 工作草稿的外键依赖一个 ACCEPTED 状态的 agent_run，按 project_qa 生命周期最小数据插入。
        jdbcTemplate.update("""
                insert into agent_run
                    (operator_id, idempotency_key, request_hash, task_type, question_hash, question_length,
                     project_id, project_identifier, branch_id, branch_name,
                     agent_name, model_name, config_summary, status, accepted_at, updated_at)
                values (?, ?, ?, 'project_qa', ?, 1,
                        null, 'GLOBAL', null, 'global',
                        'knowledge-curator', 'fake-model', 'it', 'ACCEPTED', ?, ?)
                """, "admin", "it-global-run", hash, hash, now, now);
        Long runId = jdbcTemplate.queryForObject(
                "select id from agent_run where operator_id = ? and idempotency_key = ?",
                Long.class, "admin", "it-global-run");
        jdbcTemplate.update("""
                insert into knowledge_draft
                    (id, conversation_id, operator_id, project_id, project_identifier, title, operation,
                     create_run_id, create_idempotency_key, create_request_hash, created_at, updated_at)
                values (88, ?, ?, null, 'GLOBAL', '全局草稿', 'ADD', ?, ?, ?, ?, ?)
                """, conversationId, "admin", runId, "it-global-draft", hash, now, now);

        int rows = drafts.attachConversationDraft(conversationId, "admin", null, 88L, NOW);

        assertThat(rows).isEqualTo(1);
        Long boundDraftId = jdbcTemplate.queryForObject(
                "select current_draft_id from knowledge_task_conversation where id = ?",
                Long.class, conversationId);
        assertThat(boundDraftId).isEqualTo(88L);
        System.out.printf("测试证据：场景=GLOBAL会话绑定草稿，conversationId=%s，projectId=null，绑定草稿=%s%n",
                conversationId, boundDraftId);
    }
}
