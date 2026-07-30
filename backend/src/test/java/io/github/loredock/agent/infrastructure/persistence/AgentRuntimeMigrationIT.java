package io.github.loredock.agent.infrastructure.persistence;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AgentRuntimeMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_agent_migration_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    /**
     * 业务目的：空库和已有 V5 数据库都必须只追加一次 V6，防止部署 Agent 时要求重建既有知识和代码数据。
     */
    @Test
    void emptyAndVersionFiveDatabasesMigrateOnceToSixAgentTables() throws Exception {
        Flyway empty = migrationFor("agent_empty");
        assertThat(empty.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(empty.migrate().migrationsExecuted).isZero();

        String upgradeSchema = "agent_upgrade_v5";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(upgradeSchema)
                .defaultSchema(upgradeSchema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();
        assertThat(migrationFor(upgradeSchema).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = connection()) {
            for (String table : new String[]{
                    "agent_skill_version", "agent_run", "agent_run_event",
                    "agent_tool_call", "agent_evidence", "agent_citation"}) {
                assertThat(exists(connection, "agent_empty", table)).as(table).isTrue();
                assertThat(exists(connection, upgradeSchema, table)).as("upgrade " + table).isTrue();
            }
        }
        System.out.println("测试证据：场景=V5追加V6，新增表数=6，重复迁移数=0");
    }

    /**
     * 业务目的：同名 Skill 只能有一个启用版本且同版本内容不可冲突，保证新运行固定到唯一可审计内容。
     */
    @Test
    void skillVersionAndEnabledUniquenessAreEnforced() throws Exception {
        String schema = "agent_skill_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedStoredObjects(connection, schema);
            execute(connection, skillSql(schema, "10000000-0000-0000-0000-000000000001",
                    "1.0.0", "a".repeat(64), "skill-one", "ENABLED"));

            assertThatThrownBy(() -> execute(connection, skillSql(schema,
                    "10000000-0000-0000-0000-000000000002",
                    "1.0.0", "b".repeat(64), "skill-two", "RETIRED")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, skillSql(schema,
                    "10000000-0000-0000-0000-000000000003",
                    "1.1.0", "b".repeat(64), "skill-two", "ENABLED")))
                    .isInstanceOf(SQLException.class);
        }
        System.out.println("测试证据：场景=Skill唯一启用，名称=project_qa，冲突写入均被数据库拒绝");
    }

    /**
     * 业务目的：运行幂等、状态/结果组合、问题摘要和 Token 未知语义必须由 PostgreSQL 兜底，防止绕过服务写入伪造终态。
     */
    @Test
    void runIdempotencyAndLifecycleChecksRejectInvalidFacts() throws Exception {
        String schema = "agent_run_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedRuntimeScope(connection, schema);
            execute(connection, acceptedRunSql(schema, "20000000-0000-0000-0000-000000000001", "idem-1"));

            assertThatThrownBy(() -> execute(connection,
                    acceptedRunSql(schema, "20000000-0000-0000-0000-000000000002", "idem-1")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, acceptedRunSql(schema,
                    "20000000-0000-0000-0000-000000000003", "x".repeat(129))))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, acceptedRunSql(schema,
                    "20000000-0000-0000-0000-000000000004", "idem-4")
                    .replace("'ACCEPTED', null, null", "'COMPLETED', null, null")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, eventSql(schema,
                    "30000000-0000-0000-0000-000000000099",
                    "20000000-0000-0000-0000-000000000001", 1, "RUN_ACCEPTED",
                    "{\"value\":\"" + "x".repeat(17_000) + "\"}")))
                    .isInstanceOf(SQLException.class);
        }
        System.out.println("测试证据：场景=运行事实约束，幂等冲突/超长键/非法终态/超限JSON均被拒绝");
    }

    /**
     * 业务目的：回滚到仅认识 V5 的旧应用时，V6 只能被视为 future migration，不得删表或重放历史。
     */
    @Test
    void versionFiveTargetToleratesExistingVersionSixAsFutureMigration() throws Exception {
        String schema = "agent_old_application";
        migrationFor(schema).migrate();
        int historyBefore = migrationHistoryCount(schema);

        Flyway oldApplication = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .ignoreMigrationPatterns("*:future")
                .load();

        oldApplication.validate();
        assertThat(oldApplication.migrate().migrationsExecuted).isZero();
        assertThat(migrationHistoryCount(schema)).isEqualTo(historyBefore);
        try (Connection connection = connection()) {
            assertThat(exists(connection, schema, "agent_run")).isTrue();
        }
        System.out.printf("测试证据：场景=旧应用面对future migration，迁移历史=%d，重放数=0%n", historyBefore);
    }

    /**
     * 业务目的：事件序号、证据归属和引用外键必须阻止重复或跨运行引用，避免断线续读和最终来源被污染。
     */
    @Test
    void eventAndCitationConstraintsPreventDuplicateSequenceAndCrossRunEvidence() throws Exception {
        String schema = "agent_event_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedRuntimeScope(connection, schema);
            execute(connection, acceptedRunSql(schema, "20000000-0000-0000-0000-000000000010", "idem-10"));
            execute(connection, acceptedRunSql(schema, "20000000-0000-0000-0000-000000000011", "idem-11"));
            execute(connection, eventSql(schema, "30000000-0000-0000-0000-000000000001",
                    "20000000-0000-0000-0000-000000000010", 1, "RUN_ACCEPTED", "{}"));
            assertThatThrownBy(() -> execute(connection, eventSql(schema,
                    "30000000-0000-0000-0000-000000000002",
                    "20000000-0000-0000-0000-000000000010", 1, "RUN_STARTED", "{}")))
                    .isInstanceOf(SQLException.class);

            execute(connection, evidenceSql(schema,
                    "40000000-0000-0000-0000-000000000001",
                    "20000000-0000-0000-0000-000000000010"));
            assertThatThrownBy(() -> execute(connection, citationSql(schema,
                    "50000000-0000-0000-0000-000000000001",
                    "20000000-0000-0000-0000-000000000011",
                    "40000000-0000-0000-0000-000000000001")))
                    .isInstanceOf(SQLException.class);
        }
        System.out.println("测试证据：场景=事件与引用约束，重复sequence和跨运行证据均被拒绝");
    }

    private Flyway migrationFor(String schema) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

    private int migrationHistoryCount(String schema) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + schema + ".flyway_schema_history")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void seedStoredObjects(Connection connection, String schema) throws SQLException {
        execute(connection, storedObjectSql(schema, "90000000-0000-0000-0000-000000000001", "skill-one", "a".repeat(64)));
        execute(connection, storedObjectSql(schema, "90000000-0000-0000-0000-000000000002", "skill-two", "b".repeat(64)));
    }

    private void seedRuntimeScope(Connection connection, String schema) throws SQLException {
        seedStoredObjects(connection, schema);
        execute(connection, """
                insert into %s.project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values ('11111111-1111-1111-1111-111111111111', 'atlas', 'Atlas', '', '', 'ENABLED',
                    now(), now(), 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'main',
                    now(), now(), 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.knowledge_document(id, format, title, body, directory_path, scope_type,
                    project_id, branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values ('77777777-7777-7777-7777-777777777777', 'MARKDOWN', '规则', '公开模拟正文', '/',
                    'PROJECT', '11111111-1111-1111-1111-111111111111', null, 'MANUAL', 'PUBLISHED', 1,
                    now(), 'test', now(), now(), 'test', 'test')
                """.formatted(schema));
        execute(connection, skillSql(schema, "10000000-0000-0000-0000-000000000001",
                "1.0.0", "a".repeat(64), "skill-one", "ENABLED"));
    }

    private String storedObjectSql(String schema, String id, String key, String hash) {
        return """
                insert into %s.stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('%s', '%s', 'AVAILABLE', 'skill.md', 'text/markdown', 10, '%s',
                    now(), now(), 'test', 'test')
                """.formatted(schema, id, key, hash);
    }

    private String skillSql(String schema, String id, String version, String hash, String objectKey, String status) {
        return """
                insert into %s.agent_skill_version(id, skill_name, skill_version, content_hash, object_key,
                    output_schema_version, status, created_at)
                values ('%s', 'project_qa', '%s', '%s', '%s', 'project-qa-v1', '%s', now())
                """.formatted(schema, id, version, hash, objectKey, status);
    }

    private String acceptedRunSql(String schema, String id, String idempotencyKey) {
        return """
                insert into %s.agent_run(id, operator_id, idempotency_key, request_hash, task_type,
                    question_hash, question_length, project_id, project_identifier, branch_id, branch_name,
                    skill_version_id, skill_name, skill_version, skill_content_hash, model_provider, model_name,
                    output_schema_version, tool_policy_version, limit_policy_version, status, result_type,
                    error_code, step_count, model_call_count, accepted_at, updated_at)
                values ('%s', 'member', '%s', '%s', 'project_qa', '%s', 12,
                    '11111111-1111-1111-1111-111111111111', 'atlas',
                    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'main',
                    '10000000-0000-0000-0000-000000000001', 'project_qa', '1.0.0', '%s',
                    'openai-compatible', 'deepseek-v4-flash', 'project-qa-v1', 'readonly-v1', 'limits-v1',
                    'ACCEPTED', null, null, 0, 0, now(), now())
                """.formatted(schema, id, idempotencyKey, "c".repeat(64), "d".repeat(64), "a".repeat(64));
    }

    private String eventSql(String schema, String id, String runId, long sequence, String type, String payload) {
        return """
                insert into %s.agent_run_event(id, run_id, sequence, event_type, payload, created_at)
                values ('%s', '%s', %d, '%s', '%s'::jsonb, now())
                """.formatted(schema, id, runId, sequence, type, payload);
    }

    private String evidenceSql(String schema, String id, String runId) {
        return """
                insert into %s.agent_evidence(id, run_id, evidence_key, source_type, retained, relevance,
                    document_id, project_identifier, branch_name, title, source_updated_at, metadata, created_at)
                values ('%s', '%s', 'E1', 'KNOWLEDGE', true, 0.9,
                    '77777777-7777-7777-7777-777777777777', 'atlas', 'main', '规则', now(), '{}'::jsonb, now())
                """.formatted(schema, id, runId);
    }

    private String citationSql(String schema, String id, String runId, String evidenceId) {
        return """
                insert into %s.agent_citation(id, run_id, evidence_id, citation_order, created_at)
                values ('%s', '%s', '%s', 1, now())
                """.formatted(schema, id, runId, evidenceId);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
