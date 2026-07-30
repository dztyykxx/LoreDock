package io.github.loredock.qa.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WebQaMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_web_qa_migration_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    /**
     * 业务目的：空库和既有 V6 数据库都必须只追加一次 V7，防止上线问答时重建已有 Agent 运行事实。
     */
    @Test
    void emptyAndVersionSixDatabasesMigrateOnceToSeven() throws Exception {
        Flyway empty = migrationFor("web_qa_empty");
        assertThat(empty.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(empty.migrate().migrationsExecuted).isZero();

        String upgradeSchema = "web_qa_upgrade_v6";
        migrationFor(upgradeSchema, "6").migrate();
        assertThat(migrationFor(upgradeSchema).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = connection()) {
            for (String table : new String[]{
                    "web_qa_question", "web_qa_message",
                    "knowledge_gap_feedback", "knowledge_gap_feedback_citation"}) {
                assertThat(exists(connection, "web_qa_empty", table)).as(table).isTrue();
                assertThat(exists(connection, upgradeSchema, table)).as("upgrade " + table).isTrue();
            }
            assertThat(columnExists(connection, upgradeSchema, "agent_run", "answer_basis")).isTrue();
        }
        System.out.println("测试证据：场景=V1/V6升级V7，当前版本=7，新增表数=4，重复迁移数=0");
    }

    /**
     * 业务目的：问答幂等键、运行和消息角色必须唯一，防止重试产生重复运行或重复公开消息。
     */
    @Test
    void questionAndMessageUniquenessAreEnforced() throws Exception {
        String schema = "web_qa_unique_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedRuntime(connection, schema);
            execute(connection, questionSql(schema, QUESTION_ONE, RUN_ONE, "key-one"));
            execute(connection, messageSql(schema, MESSAGE_ONE, QUESTION_ONE, "USER"));

            assertThatThrownBy(() -> execute(connection,
                    questionSql(schema, QUESTION_TWO, RUN_TWO, "key-one")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    questionSql(schema, QUESTION_TWO, RUN_ONE, "key-two")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    messageSql(schema, MESSAGE_TWO, QUESTION_ONE, "USER")))
                    .isInstanceOf(SQLException.class);
            assertThat(hasIndex(connection, schema, "web_qa_question", "created_at", "id")).isTrue();
        }
        System.out.println("测试证据：场景=问答唯一事实，操作者键/运行/消息角色冲突均被拒绝，游标索引=true");
    }

    /**
     * 业务目的：反馈类型和处理状态必须由数据库限定，防止未知枚举或非法状态污染管理员队列。
     */
    @Test
    void feedbackEnumsStatusAndIdempotencyAreEnforced() throws Exception {
        String schema = "knowledge_gap_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedRuntime(connection, schema);
            execute(connection, feedbackSql(schema, FEEDBACK_ONE, "gap-one", "NO_ANSWER", "OPEN", null, null));

            assertThatThrownBy(() -> execute(connection,
                    feedbackSql(schema, FEEDBACK_TWO, "gap-two", "UNKNOWN", "OPEN", null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    feedbackSql(schema, FEEDBACK_TWO, "gap-two", "WRONG_ANSWER", "DELETED", null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    feedbackSql(schema, FEEDBACK_TWO, "gap-one", "OUTDATED_KNOWLEDGE", "OPEN", null, null)))
                    .isInstanceOf(SQLException.class);
            assertThat(hasIndex(connection, schema, "knowledge_gap_feedback", "created_at", "id")).isTrue();
        }
        System.out.println("测试证据：场景=反馈约束，类型/状态/幂等冲突均被拒绝，游标索引=true");
    }

    /**
     * 业务目的：反馈引用只能关联该反馈运行内的真实证据，防止管理员看到被跨运行拼接的来源。
     */
    @Test
    void feedbackCitationCompositeForeignKeyRejectsCrossRunEvidence() throws Exception {
        String schema = "knowledge_gap_citation_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedRuntime(connection, schema);
            execute(connection, questionSql(schema, QUESTION_ONE, RUN_ONE, "key-one"));
            execute(connection, feedbackSql(
                    schema, FEEDBACK_ONE, "gap-one", "WRONG_ANSWER", "OPEN", QUESTION_ONE, RUN_ONE));
            execute(connection, evidenceSql(schema, EVIDENCE_ONE, RUN_ONE, "E1"));
            execute(connection, evidenceSql(schema, EVIDENCE_TWO, RUN_TWO, "E1"));
            execute(connection, feedbackCitationSql(
                    schema, FEEDBACK_CITATION_ONE, FEEDBACK_ONE, RUN_ONE, EVIDENCE_ONE));

            assertThatThrownBy(() -> execute(connection, feedbackCitationSql(
                    schema, FEEDBACK_CITATION_TWO, FEEDBACK_ONE, RUN_ONE, EVIDENCE_TWO)))
                    .isInstanceOf(SQLException.class);
        }
        System.out.println("测试证据：场景=反馈引用同运行约束，合法引用数=1，跨运行引用被拒绝");
    }

    /**
     * 业务目的：旧应用必须能忽略 V7，且新增回答依据保持可空，保证数据库升级后的应用回滚不破坏 T6A。
     */
    @Test
    void versionSixApplicationToleratesVersionSevenAndNullableAnswerBasis() throws Exception {
        String schema = "web_qa_old_application";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedRuntime(connection, schema);
            assertThat(queryLong(connection,
                    "select count(*) from " + schema + ".agent_run where answer_basis is null"))
                    .isEqualTo(2);
        }

        Flyway oldApplication = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("6"))
                .ignoreMigrationPatterns("*:future")
                .load();
        oldApplication.validate();
        assertThat(oldApplication.migrate().migrationsExecuted).isZero();
        System.out.println("测试证据：场景=V7回滚兼容，旧应用目标版本=6，空回答依据运行数=2，重放数=0");
    }

    /**
     * 业务目的：问答正文写入必须遵守事务和 UTC 时间事实，防止失败创建留下孤立历史或发生时区漂移。
     */
    @Test
    void questionInsertRollsBackAndTimestampKeepsSameInstant() throws Exception {
        String schema = "web_qa_transaction_time";
        migrationFor(schema).migrate();
        Instant createdAt = Instant.parse("2026-07-30T03:04:05Z");
        try (Connection connection = connection()) {
            seedRuntime(connection, schema);
            connection.setAutoCommit(false);
            execute(connection, questionSql(schema, QUESTION_ONE, RUN_ONE, "key-one")
                    .replace("now()", "'" + createdAt + "'::timestamptz"));
            connection.rollback();
            assertThat(queryLong(connection, "select count(*) from " + schema + ".web_qa_question")).isZero();

            connection.setAutoCommit(true);
            execute(connection, questionSql(schema, QUESTION_ONE, RUN_ONE, "key-one")
                    .replace("now()", "'" + createdAt + "'::timestamptz"));
            try (var statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "select created_at from " + schema + ".web_qa_question where id='" + QUESTION_ONE + "'")) {
                result.next();
                assertThat(result.getTimestamp(1).toInstant()).isEqualTo(createdAt);
            }
        }
        System.out.printf("测试证据：场景=问答事务与UTC，回滚行数=0，持久化时间=%s%n", createdAt);
    }

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String BRANCH_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SKILL_ID = "10000000-0000-0000-0000-000000000001";
    private static final String RUN_ONE = "20000000-0000-0000-0000-000000000001";
    private static final String RUN_TWO = "20000000-0000-0000-0000-000000000002";
    private static final String QUESTION_ONE = "30000000-0000-0000-0000-000000000001";
    private static final String QUESTION_TWO = "30000000-0000-0000-0000-000000000002";
    private static final String MESSAGE_ONE = "40000000-0000-0000-0000-000000000001";
    private static final String MESSAGE_TWO = "40000000-0000-0000-0000-000000000002";
    private static final String FEEDBACK_ONE = "50000000-0000-0000-0000-000000000001";
    private static final String FEEDBACK_TWO = "50000000-0000-0000-0000-000000000002";
    private static final String EVIDENCE_ONE = "60000000-0000-0000-0000-000000000001";
    private static final String EVIDENCE_TWO = "60000000-0000-0000-0000-000000000002";
    private static final String FEEDBACK_CITATION_ONE = "70000000-0000-0000-0000-000000000001";
    private static final String FEEDBACK_CITATION_TWO = "70000000-0000-0000-0000-000000000002";

    private Flyway migrationFor(String schema) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
    }

    private Flyway migrationFor(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target)).load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void seedRuntime(Connection connection, String schema) throws SQLException {
        execute(connection, """
                insert into %s.stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('90000000-0000-0000-0000-000000000001', 'skill-one', 'AVAILABLE', 'skill.md',
                    'text/markdown', 10, '%s', now(), now(), 'test', 'test')
                """.formatted(schema, "a".repeat(64)));
        execute(connection, """
                insert into %s.project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values ('%s', 'atlas', 'Atlas', '', '', 'ENABLED', now(), now(), 'test', 'test')
                """.formatted(schema, PROJECT_ID));
        execute(connection, """
                insert into %s.project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values ('%s', '%s', 'main', now(), now(), 'test', 'test')
                """.formatted(schema, BRANCH_ID, PROJECT_ID));
        execute(connection, """
                insert into %s.knowledge_document(id, format, title, body, directory_path, scope_type,
                    project_id, branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values ('80000000-0000-0000-0000-000000000001', 'MARKDOWN', '规则', '模拟正文', '/',
                    'PROJECT', '%s', null, 'MANUAL', 'PUBLISHED', 1, now(), 'test', now(), now(), 'test', 'test')
                """.formatted(schema, PROJECT_ID));
        execute(connection, """
                insert into %s.agent_skill_version(id, skill_name, skill_version, content_hash, object_key,
                    output_schema_version, status, created_at)
                values ('%s', 'project_qa', '1.0.0', '%s', 'skill-one', 'project-qa-v1', 'ENABLED', now())
                """.formatted(schema, SKILL_ID, "a".repeat(64)));
        execute(connection, runSql(schema, RUN_ONE, "agent-key-one"));
        execute(connection, runSql(schema, RUN_TWO, "agent-key-two"));
    }

    private String runSql(String schema, String runId, String idempotencyKey) {
        return """
                insert into %s.agent_run(id, operator_id, idempotency_key, request_hash, task_type,
                    question_hash, question_length, project_id, project_identifier, branch_id, branch_name,
                    skill_version_id, skill_name, skill_version, skill_content_hash, model_provider, model_name,
                    output_schema_version, tool_policy_version, limit_policy_version, status, accepted_at, updated_at)
                values ('%s', 'member', '%s', '%s', 'project_qa', '%s', 8, '%s', 'atlas', '%s', 'main',
                    '%s', 'project_qa', '1.0.0', '%s', 'fake', 'fake-model', 'project-qa-v1',
                    'readonly-v1', 'limits-v1', 'ACCEPTED', now(), now())
                """.formatted(schema, runId, idempotencyKey, "b".repeat(64), "c".repeat(64),
                PROJECT_ID, BRANCH_ID, SKILL_ID, "a".repeat(64));
    }

    private String questionSql(String schema, String questionId, String runId, String idempotencyKey) {
        return """
                insert into %s.web_qa_question(id, operator_id, idempotency_key, request_hash, project_id,
                    project_identifier, branch_id, branch_name, run_id, created_at)
                values ('%s', 'member', '%s', '%s', '%s', 'atlas', '%s', 'main', '%s', now())
                """.formatted(schema, questionId, idempotencyKey, "d".repeat(64), PROJECT_ID, BRANCH_ID, runId);
    }

    private String messageSql(String schema, String messageId, String questionId, String role) {
        return """
                insert into %s.web_qa_message(id, question_id, role, content, result_type, refusal_reason, created_at)
                values ('%s', '%s', '%s', '为什么这样设计？', null, null, now())
                """.formatted(schema, messageId, questionId, role);
    }

    private String feedbackSql(
            String schema, String feedbackId, String key, String type, String status,
            String questionId, String runId
    ) {
        String questionValue = questionId == null ? "null" : "'" + questionId + "'";
        String runValue = runId == null ? "null" : "'" + runId + "'";
        return """
                insert into %s.knowledge_gap_feedback(id, operator_id, idempotency_key, request_hash,
                    project_id, project_identifier, branch_id, branch_name, question_id, run_id,
                    gap_type, status, question_text, note, result_type, refusal_reason, error_code,
                    created_at, updated_at, created_by, updated_by)
                values ('%s', 'member', '%s', '%s', '%s', 'atlas', '%s', 'main', %s, %s,
                    '%s', '%s', '为什么这样设计？', null, null, null, null, now(), now(), 'member', 'member')
                """.formatted(schema, feedbackId, key, "e".repeat(64), PROJECT_ID, BRANCH_ID,
                questionValue, runValue, type, status);
    }

    private String evidenceSql(String schema, String evidenceId, String runId, String key) {
        return """
                insert into %s.agent_evidence(id, run_id, evidence_key, source_type, retained, relevance,
                    document_id, project_identifier, branch_name, title, source_updated_at, metadata, created_at)
                values ('%s', '%s', '%s', 'KNOWLEDGE', true, 0.9,
                    '80000000-0000-0000-0000-000000000001', 'atlas', 'main', '规则', now(), '{}'::jsonb, now())
                """.formatted(schema, evidenceId, runId, key);
    }

    private String feedbackCitationSql(
            String schema, String id, String feedbackId, String runId, String evidenceId
    ) {
        return """
                insert into %s.knowledge_gap_feedback_citation(
                    id, feedback_id, run_id, evidence_id, citation_order, created_at)
                values ('%s', '%s', '%s', '%s', 1, now())
                """.formatted(schema, id, feedbackId, runId, evidenceId);
    }

    private boolean exists(Connection connection, String schema, String table) throws SQLException {
        try (var statement = connection.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, schema + "." + table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private boolean columnExists(Connection connection, String schema, String table, String column)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select exists(select 1 from information_schema.columns
                    where table_schema=? and table_name=? and column_name=?)
                """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private boolean hasIndex(Connection connection, String schema, String table, String first, String second)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select exists(select 1 from pg_indexes
                    where schemaname=? and tablename=? and indexdef like ? and indexdef like ?)
                """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, "%" + first + "%");
            statement.setString(4, "%" + second + "%");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
