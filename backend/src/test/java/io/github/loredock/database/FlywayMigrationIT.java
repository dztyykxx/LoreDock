package io.github.loredock.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @TempDir
    private Path temporaryDirectory;

    /**
     * 业务目的：空数据库必须一次迁移成后续存储和后台任务可依赖的结构，防止部署后应用就绪但基础表或向量扩展缺失。
     */
    @Test
    void emptyDatabaseMigrationEnablesVectorAndCreatesFoundationSchema() throws Exception {
        Flyway flyway = migrationFor("foundation");

        flyway.migrate();

        try (Connection connection = connection()) {
            assertThat(queryBoolean(connection,
                    "select exists(select 1 from pg_extension where extname = 'vector')"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.stored_object') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.background_job') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, """
                    select exists(
                        select 1 from pg_constraint
                        where conrelid = 'foundation.background_job'::regclass
                          and conname = 'ck_background_job_progress'
                    )
                    """))
                    .isTrue();
        }
    }

    /**
     * 业务目的：应用重复启动不能重复执行已成功迁移，防止重建表、覆盖数据或污染迁移历史。
     */
    @Test
    void migratedDatabaseDoesNotRepeatMigration() throws Exception {
        Flyway flyway = migrationFor("repeatable_start");
        flyway.migrate();
        int historyCount = migrationHistoryCount("repeatable_start");

        var secondResult = flyway.migrate();

        assertThat(secondResult.migrationsExecuted).isZero();
        assertThat(migrationHistoryCount("repeatable_start")).isEqualTo(historyCount);
    }

    /**
     * 业务目的：已执行的版本化迁移不得被静默修改，防止不同环境拥有相同版本号却形成不同数据库结构。
     */
    @Test
    void changedAppliedMigrationFailsValidation() throws Exception {
        Path migrationDirectory = Files.createDirectory(temporaryDirectory.resolve("checksum"));
        Path migration = migrationDirectory.resolve("V1__create_marker.sql");
        Files.writeString(migration, "create table checksum_marker(id integer primary key);\n");
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("checksum_guard")
                .defaultSchema("checksum_guard")
                .locations("filesystem:" + migrationDirectory)
                .load();
        flyway.migrate();

        Files.writeString(migration, "create table checksum_marker(id bigint primary key);\n");

        assertThatThrownBy(flyway::validate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");
    }

    /**
     * 业务目的：已有 T1 数据库必须只追加升级到 T2 项目结构，防止修改 V1 或要求部署时重建基础表。
     */
    @Test
    void versionOneDatabaseUpgradesInPlaceToProjectSchema() throws Exception {
        String schema = "upgrade_from_v1";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
        int versionOneHistoryCount = migrationHistoryCount(schema);

        try (Connection connection = connection()) {
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".stored_object') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".project_space') is null"))
                    .isTrue();
        }

        Flyway upgraded = migrationFor(schema);
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migrationHistoryCount(schema)).isEqualTo(versionOneHistoryCount + 1);
        try (Connection connection = connection()) {
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".project_space') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".project_branch') is not null"))
                    .isTrue();
        }
    }

    /**
     * 业务目的：项目状态、唯一范围、外键和 UTC 审计必须由真实 PostgreSQL 兜底，防止并发或绕过应用层写出非法范围。
     */
    @Test
    void projectSchemaEnforcesStatusUniquenessForeignKeyAndAuditConstraints() throws Exception {
        String schema = "project_constraints";
        migrationFor(schema).migrate();
        String projectOne = "11111111-1111-1111-1111-111111111111";
        String projectTwo = "22222222-2222-2222-2222-222222222222";

        try (Connection connection = connection()) {
            execute(connection, insertProject(schema, projectOne, "network-tool", "ENABLED"));
            execute(connection, insertProject(schema, projectTwo, "comparison-tool", "DISABLED"));
            execute(connection, insertBranch(schema, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", projectOne, "main"));
            execute(connection, insertBranch(schema, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", projectTwo, "main"));

            assertThatThrownBy(() -> execute(connection,
                    insertProject(schema, "33333333-3333-3333-3333-333333333333", "network-tool", "ENABLED")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    insertProject(schema, "44444444-4444-4444-4444-444444444444", "bad-status", "ARCHIVED")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    insertBranch(schema, "cccccccc-cccc-cccc-cccc-cccccccccccc", projectOne, "main")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection,
                    insertBranch(schema, "dddddddd-dddd-dddd-dddd-dddddddddddd", "99999999-9999-9999-9999-999999999999", "main")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    insert into %s.project_space(
                        id, identifier, name, description, technology_stack, status,
                        created_at, updated_at, created_by, updated_by
                    ) values (
                        '55555555-5555-5555-5555-555555555555', 'bad-audit', 'Bad audit', '', '', 'ENABLED',
                        '2026-07-30T01:00:00Z', '2026-07-30T00:00:00Z', 'admin', 'admin'
                    )
                    """.formatted(schema)))
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

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private boolean queryBoolean(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private int migrationHistoryCount(String schema) throws Exception {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + schema + ".flyway_schema_history")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String insertProject(String schema, String id, String identifier, String status) {
        return """
                insert into %s.project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by
                ) values (
                    '%s', '%s', 'Project', '', '', '%s',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'admin', 'admin'
                )
                """.formatted(schema, id, identifier, status);
    }

    private String insertBranch(String schema, String id, String projectId, String name) {
        return """
                insert into %s.project_branch(
                    id, project_id, name, created_at, updated_at, created_by, updated_by
                ) values (
                    '%s', '%s', '%s',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'admin', 'admin'
                )
                """.formatted(schema, id, projectId, name);
    }
}
