package io.github.loredock.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证快速迭代阶段的单一数据库基线。 */
@Testcontainers
class FlywayMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    /**
     * 业务目的：全新数据库必须由一次基线迁移建立当前全部核心表，防止继续依赖已删除的历史升级链。
     */
    @Test
    void emptyDatabaseCreatesCurrentSchemaWithOneBaseline() throws Exception {
        String schema = "empty_baseline";
        var result = migrationFor(schema).migrate();

        assertThat(result.migrationsExecuted).isOne();
        try (Connection connection = connection()) {
            assertThat(queryInt(connection, """
                    select count(*)
                    from information_schema.tables
                    where table_schema = 'empty_baseline'
                      and table_type = 'BASE TABLE'
                      and table_name <> 'flyway_schema_history'
                    """)).isEqualTo(19);
            for (String table : new String[]{
                    "stored_object", "background_job", "project_space", "project_branch",
                    "knowledge_document", "knowledge_import_batch",
                    "knowledge_index_generation", "knowledge_search_chunk",
                    "code_snapshot", "code_index_generation",
                    "agent_run", "agent_run_event", "agent_evidence",
                    "graphthread", "graphcheckpoint",
                    "web_qa_question", "web_qa_message",
                    "knowledge_gap_feedback", "knowledge_gap_feedback_citation"}) {
                assertThat(tableExists(connection, schema, table)).as(table).isTrue();
            }
            for (String removedTable : new String[]{
                    "knowledge_document_tag", "knowledge_import_item",
                    "knowledge_index_document", "knowledge_search_generation",
                    "agent_skill_version", "agent_tool_call", "agent_citation"}) {
                assertThat(tableExists(connection, schema, removedTable)).as(removedTable).isFalse();
            }
        }
        System.out.println("测试证据：场景=全新数据库单基线初始化，迁移数=1，当前表数=19，已删除旧表数=7");
    }

    /**
     * 业务目的：应用重复启动不能重复执行基线，防止重建表、覆盖数据或污染迁移历史。
     */
    @Test
    void migratedDatabaseDoesNotRepeatBaseline() throws Exception {
        String schema = "repeatable_baseline";
        Flyway flyway = migrationFor(schema);

        assertThat(flyway.migrate().migrationsExecuted).isOne();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = connection()) {
            assertThat(queryInt(connection,
                    "select count(*) from repeatable_baseline.flyway_schema_history "
                            + "where success and version = '1'"))
                    .isOne();
        }
        System.out.println("测试证据：场景=重复启动，首次迁移数=1，第二次迁移数=0，成功历史版本数=1");
    }

    /**
     * 业务目的：最终基线必须在数据库层保护项目与分支外键，防止绕过 Service 写入悬空范围。
     */
    @Test
    void baselineEnforcesCoreForeignKey() throws Exception {
        String schema = "baseline_foreign_key";
        migrationFor(schema).migrate();

        try (Connection connection = connection()) {
            assertThatThrownBy(() -> execute(connection, """
                    insert into baseline_foreign_key.project_branch(
                        project_id, name, created_at, updated_at, created_by, updated_by
                    ) values (
                        999999, 'main', now(), now(), 'test', 'test'
                    )
                    """)).isInstanceOf(SQLException.class);
        }
        System.out.println("测试证据：场景=基线外键约束，不存在项目ID=999999，分支写入结果=拒绝");
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
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select exists(
                    select 1 from information_schema.tables
                    where table_schema = ? and table_name = ?
                )
                """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private int queryInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
