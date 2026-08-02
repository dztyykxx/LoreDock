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

/** 验证快速迭代阶段的当前数据库基线与后续兼容迁移。 */
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
    void emptyDatabaseCreatesCurrentSchemaWithConversationMigration() throws Exception {
        String schema = "empty_baseline";
        var result = migrationFor(schema).migrate();

        assertThat(result.migrationsExecuted).isEqualTo(5);
        try (Connection connection = connection()) {
            assertThat(queryInt(connection, """
                    select count(*)
                    from information_schema.tables
                    where table_schema = 'empty_baseline'
                      and table_type = 'BASE TABLE'
                      and table_name <> 'flyway_schema_history'
                    """)).isEqualTo(29);
            for (String table : new String[]{
                    "stored_object", "background_job", "project_space", "project_branch",
                    "knowledge_document", "knowledge_import_batch",
                    "knowledge_index_generation", "knowledge_search_chunk",
                    "code_snapshot", "code_index_generation",
                    "agent_run", "agent_run_event", "agent_evidence",
                    "graphthread", "graphcheckpoint",
                    "web_qa_conversation", "web_qa_question", "web_qa_message",
                    "knowledge_gap_feedback", "knowledge_gap_feedback_citation",
                    "knowledge_task_conversation", "knowledge_task_message",
                    "knowledge_draft", "knowledge_draft_revision", "knowledge_draft_revision_source",
                    "knowledge_task_selected_draft", "knowledge_tool_invocation",
                    "knowledge_task_event", "knowledge_task_publication"}) {
                assertThat(tableExists(connection, schema, table)).as(table).isTrue();
            }
            for (String removedTable : new String[]{
                    "knowledge_document_tag", "knowledge_import_item",
                    "knowledge_index_document", "knowledge_search_generation",
                    "agent_skill_version", "agent_tool_call", "agent_citation"}) {
                assertThat(tableExists(connection, schema, removedTable)).as(removedTable).isFalse();
            }
        }
        System.out.println("测试证据：场景=全新数据库初始化，迁移数=5，当前表数=29，多文档任务表=存在");
    }

    /**
     * 业务目的：应用重复启动不能重复执行基线，防止重建表、覆盖数据或污染迁移历史。
     */
    @Test
    void migratedDatabaseDoesNotRepeatBaseline() throws Exception {
        String schema = "repeatable_baseline";
        Flyway flyway = migrationFor(schema);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = connection()) {
            assertThat(queryInt(connection,
                    "select count(*) from repeatable_baseline.flyway_schema_history "
                            + "where success and version is not null"))
                    .isEqualTo(5);
        }
        System.out.println("测试证据：场景=重复启动，首次迁移数=5，第二次迁移数=0，成功历史版本数=5");
    }

    /**
     * 业务目的：T7 已保存的孤立问题升级后必须保留 questionId/runId，并自动成为一题一会话，防止历史 URL 失效。
     */
    @Test
    void versionOneQuestionIsBackfilledAsSingleRoundConversation() throws Exception {
        String schema = "conversation_upgrade";
        migrationFor(schema, "1").migrate();
        try (Connection connection = connection()) {
            execute(connection, "set search_path to " + schema);
            execute(connection, """
                    insert into project_space(id, identifier, name, description, technology_stack, status,
                        created_at, updated_at, created_by, updated_by)
                    values (101, 'atlas', 'Atlas', '', 'Java', 'ENABLED', now(), now(), 'test', 'test')
                    """);
            execute(connection, """
                    insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                    values (102, 101, 'main', now(), now(), 'test', 'test')
                    """);
            execute(connection, """
                    insert into agent_run(id, operator_id, idempotency_key, request_hash, task_type,
                        question_hash, question_length, project_id, project_identifier, branch_id, branch_name,
                        agent_name, model_name, config_summary, status, accepted_at, updated_at)
                    values (103, 'member', 'agent-key', repeat('a', 64), 'project_qa', repeat('b', 64), 6,
                        101, 'atlas', 102, 'main', 'project_qa', 'fake-model', 'project-qa-v1',
                        'ACCEPTED', now(), now())
                    """);
            execute(connection, """
                    insert into web_qa_question(id, operator_id, idempotency_key, request_hash, project_id,
                        project_identifier, branch_id, branch_name, run_id, created_at)
                    values (104, 'member', 'client-key', repeat('c', 64), 101, 'atlas', 102, 'main', 103, now())
                    """);
            execute(connection, """
                    insert into web_qa_message(id, question_id, role, content, created_at)
                    values (105, 104, 'USER', '为什么这样设计？', now())
                    """);
        }

        var result = migrationFor(schema).migrate();

        assertThat(result.migrationsExecuted).isEqualTo(4);
        try (Connection connection = connection()) {
            assertThat(queryInt(connection, """
                    select count(*) from conversation_upgrade.web_qa_question q
                    join conversation_upgrade.web_qa_conversation c on c.id = q.conversation_id
                    where q.id = 104 and q.run_id = 103 and c.operator_id = 'member'
                      and c.project_id = 101 and c.title = '为什么这样设计？'
                    """)).isOne();
        }
        System.out.println("测试证据：场景=V1问答兼容升级，questionId=104，runId=103，会话轮次数=1，原URL身份=保留");
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

    private Flyway migrationFor(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
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
