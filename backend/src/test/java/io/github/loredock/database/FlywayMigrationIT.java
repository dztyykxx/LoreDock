package io.github.loredock.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
    void 空数据库迁移后启用向量扩展并建立基础表与约束() throws Exception {
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
    void 已迁移数据库再次执行时不重复迁移() throws Exception {
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
    void 已执行迁移内容改变时校验失败() throws Exception {
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
}
