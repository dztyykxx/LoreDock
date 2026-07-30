package io.github.loredock.code.infrastructure.persistence;

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
class CodeSnapshotMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_code_migration_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    /**
     * 业务目的：空库与已有 V1～V3 数据库都必须只追加一次 V4，防止部署要求重建既有项目、知识或任务数据。
     */
    @Test
    void emptyAndVersionThreeDatabasesMigrateOnceToCodeSnapshotSchema() throws Exception {
        Flyway empty = migrationFor("code_empty", "4");
        assertThat(empty.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(empty.migrate().migrationsExecuted).isZero();

        String upgradeSchema = "code_upgrade_v3";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(upgradeSchema)
                .defaultSchema(upgradeSchema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("3"))
                .load()
                .migrate();
        assertThat(migrationFor(upgradeSchema, "4").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = connection()) {
            assertThat(exists(connection, "code_empty", "code_snapshot")).isTrue();
            assertThat(exists(connection, "code_empty", "code_index_generation")).isTrue();
            assertThat(exists(connection, upgradeSchema, "code_snapshot")).isTrue();
            assertThat(exists(connection, upgradeSchema, "code_index_generation")).isTrue();
        }
    }

    /**
     * 业务目的：commit、状态时间和计数必须由 PostgreSQL 兜底，防止绕过应用层写出可被误认成活动版本的非法元数据。
     */
    @Test
    void snapshotAndGenerationChecksRejectInvalidLifecycleMetadata() throws Exception {
        String schema = "code_checks";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedScopeAndObject(connection, schema);
            assertThatThrownBy(() -> execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000001", "not-a-commit",
                    "CANDIDATE", 0, 0, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000002", "abcdef1",
                    "ACTIVE", -1, 0, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000003", "abcdef1",
                    "ACTIVE", 1, 0, null, null)))
                    .isInstanceOf(SQLException.class);

            execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000004", "abcdef1",
                    "CANDIDATE", 0, 0, null, null));
            execute(connection, jobSql(schema, "20000000-0000-0000-0000-000000000001",
                    "10000000-0000-0000-0000-000000000004", "CODE_SNAPSHOT_BUILD", "PENDING"));
            assertThatThrownBy(() -> execute(connection, generationSql(
                    schema, "30000000-0000-0000-0000-000000000001",
                    "10000000-0000-0000-0000-000000000004",
                    "20000000-0000-0000-0000-000000000001", "ACTIVE", -1, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, generationSql(
                    schema, "30000000-0000-0000-0000-000000000002",
                    "10000000-0000-0000-0000-000000000004",
                    "20000000-0000-0000-0000-000000000001", "ACTIVE", 1, null)))
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * 业务目的：范围、对象、快照和任务外键以及两个活动唯一约束必须由数据库保护，防止并发激活或悬空引用污染查询入口。
     */
    @Test
    void foreignKeysAndActiveUniquenessProtectSnapshotScopeAndQueryEntry() throws Exception {
        String schema = "code_relations";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedScopeAndObject(connection, schema);
            assertThatThrownBy(() -> execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000010", "abcdef1", "CANDIDATE",
                    0, 0, null, "missing-object")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, snapshotSqlForScope(
                    schema, "10000000-0000-0000-0000-000000000011",
                    "99999999-9999-9999-9999-999999999999",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "object-key")))
                    .isInstanceOf(SQLException.class);

            execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000012", "abcdef1", "ACTIVE",
                    1, 0, "2026-07-30T01:00:00Z", null));
            assertThatThrownBy(() -> execute(connection, snapshotSql(
                    schema, "10000000-0000-0000-0000-000000000013", "abcdef2", "ACTIVE",
                    1, 0, "2026-07-30T02:00:00Z", null)))
                    .isInstanceOf(SQLException.class);

            execute(connection, jobSql(schema, "20000000-0000-0000-0000-000000000010",
                    "10000000-0000-0000-0000-000000000012", "CODE_SNAPSHOT_BUILD", "SUCCEEDED"));
            execute(connection, generationSql(
                    schema, "30000000-0000-0000-0000-000000000010",
                    "10000000-0000-0000-0000-000000000012",
                    "20000000-0000-0000-0000-000000000010", "ACTIVE", 1,
                    "2026-07-30T01:00:00Z"));
            execute(connection, jobSql(schema, "20000000-0000-0000-0000-000000000011",
                    "10000000-0000-0000-0000-000000000012", "CODE_SNAPSHOT_REINDEX", "SUCCEEDED"));
            assertThatThrownBy(() -> execute(connection, generationSql(
                    schema, "30000000-0000-0000-0000-000000000011",
                    "10000000-0000-0000-0000-000000000012",
                    "20000000-0000-0000-0000-000000000011", "ACTIVE", 1,
                    "2026-07-30T02:00:00Z")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, generationSql(
                    schema, "30000000-0000-0000-0000-000000000012",
                    "10000000-0000-0000-0000-000000000012",
                    "29999999-9999-9999-9999-999999999999", "BUILDING", 0, null)))
                    .isInstanceOf(SQLException.class);
        }
    }

    private Flyway migrationFor(String schema) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
    }

    private Flyway migrationFor(String schema, String targetVersion) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(targetVersion))
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

    private void seedScopeAndObject(Connection connection, String schema) throws SQLException {
        execute(connection, """
                insert into %s.project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values ('11111111-1111-1111-1111-111111111111', 'network-tool', 'Network Tool', '', '', 'ENABLED',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'main',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'object-key', 'AVAILABLE', 'code.zip',
                    'application/zip', 4, '%s', '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema, "a".repeat(64)));
    }

    private String snapshotSql(
            String schema, String id, String commit, String status, long indexed, long ignored,
            String indexedAt, String objectKey
    ) {
        return snapshotSqlForScopeAndState(schema, id,
                "11111111-1111-1111-1111-111111111111", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                objectKey == null ? "object-key" : objectKey, commit, status, indexed, ignored, indexedAt);
    }

    private String snapshotSqlForScope(String schema, String id, String projectId, String branchId, String objectKey) {
        return snapshotSqlForScopeAndState(schema, id, projectId, branchId, objectKey,
                "abcdef1", "CANDIDATE", 0, 0, null);
    }

    private String snapshotSqlForScopeAndState(
            String schema, String id, String projectId, String branchId, String objectKey,
            String commit, String status, long indexed, long ignored, String indexedAt
    ) {
        return """
                insert into %s.code_snapshot(id, project_id, branch_id, commit_hash, input_object_key, status,
                    previous_snapshot_id, indexed_file_count, ignored_file_count, indexed_at,
                    created_at, updated_at, created_by, updated_by)
                values ('%s', '%s', '%s', '%s', '%s', '%s', null, %d, %d, %s,
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema, id, projectId, branchId, commit, objectKey, status, indexed, ignored,
                indexedAt == null ? "null" : "'" + indexedAt + "'");
    }

    private String jobSql(String schema, String id, String snapshotId, String type, String status) {
        return """
                insert into %s.background_job(id, job_type, status, progress, project_id, branch_id, snapshot_id,
                    created_at, updated_at, created_by, updated_by)
                values ('%s', '%s', '%s', 0, '11111111-1111-1111-1111-111111111111',
                    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '%s',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema, id, type, status, snapshotId);
    }

    private String generationSql(
            String schema, String id, String snapshotId, String jobId,
            String status, long documentCount, String activatedAt
    ) {
        return """
                insert into %s.code_index_generation(id, snapshot_id, job_id, status, document_count,
                    created_at, activated_at)
                values ('%s', '%s', '%s', '%s', %d, '2026-07-30T00:00:00Z', %s)
                """.formatted(schema, id, snapshotId, jobId, status, documentCount,
                activatedAt == null ? "null" : "'" + activatedAt + "'");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
