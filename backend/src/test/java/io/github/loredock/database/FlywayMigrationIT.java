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
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.knowledge_document') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.knowledge_import_batch') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.knowledge_index_generation') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.code_snapshot') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.code_index_generation') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.knowledge_search_generation') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.knowledge_search_chunk') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.agent_run') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('foundation.agent_citation') is not null"))
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
     * 业务目的：已有 T1 数据库必须只追加升级到当前结构，防止修改 V1 或要求部署时重建基础表。
     */
    @Test
    void versionOneDatabaseUpgradesInPlaceToCurrentSchema() throws Exception {
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
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(migrationHistoryCount(schema)).isEqualTo(versionOneHistoryCount + 5);
        try (Connection connection = connection()) {
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".project_space') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".project_branch') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".knowledge_document') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".code_snapshot') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                    "select to_regclass('" + schema + ".knowledge_search_generation') is not null"))
                    .isTrue();
            assertThat(queryBoolean(connection, "select to_regclass('" + schema + ".agent_run') is not null"))
                    .isTrue();
        }
    }

    /**
     * 业务目的：已有 T2 数据库必须逐版追加到当前结构，防止要求重建项目、分支或基础任务数据。
     */
    @Test
    void versionTwoDatabaseUpgradesInPlaceToCurrentSchema() throws Exception {
        String schema = "upgrade_from_v2";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
        int versionTwoHistoryCount = migrationHistoryCount(schema);

        Flyway upgraded = migrationFor(schema);
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(migrationHistoryCount(schema)).isEqualTo(versionTwoHistoryCount + 4);
        try (Connection connection = connection()) {
            for (String table : new String[]{
                    "knowledge_document", "knowledge_document_tag", "knowledge_import_batch",
                    "knowledge_import_item", "knowledge_index_generation", "knowledge_index_document",
                    "code_snapshot", "code_index_generation",
                    "knowledge_search_generation", "knowledge_search_chunk",
                    "agent_skill_version", "agent_run", "agent_run_event", "agent_tool_call",
                    "agent_evidence", "agent_citation"}) {
                assertThat(queryBoolean(connection,
                        "select to_regclass('" + schema + "." + table + "') is not null"))
                        .as(table)
                        .isTrue();
            }
        }
    }

    /**
     * 业务目的：文档范围、来源、状态、revision 及项目/分支外键必须由 PostgreSQL 兜底，防止绕过领域层写入越界知识。
     */
    @Test
    void knowledgeDocumentSchemaEnforcesScopeSourceStatusRevisionAndForeignKeys() throws Exception {
        String schema = "knowledge_document_constraints";
        migrationFor(schema).migrate();
        String projectId = "11111111-1111-1111-1111-111111111111";
        String branchId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

        try (Connection connection = connection()) {
            execute(connection, insertProject(schema, projectId, "network-tool", "ENABLED"));
            execute(connection, insertBranch(schema, branchId, projectId, "main"));
            execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000001",
                    "GLOBAL", null, null, "MANUAL", null, null,
                    "DRAFT", 1, null, null, null, null, null));
            execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000002",
                    "BRANCH", projectId, branchId, "WIKI", "https://example.test/wiki", null,
                    "PUBLISHED", 2, "2026-07-30T01:00:00Z", "admin", null, null, null));

            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000003",
                    "GLOBAL", projectId, null, "MANUAL", null, null,
                    "DRAFT", 1, null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000004",
                    "BRANCH", projectId, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "MANUAL", null, null,
                    "DRAFT", 1, null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000005",
                    "GLOBAL", null, null, "WIKI", null, null,
                    "DRAFT", 1, null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000006",
                    "GLOBAL", null, null, "UPLOAD", null, null,
                    "DRAFT", 1, null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000007",
                    "GLOBAL", null, null, "MANUAL", null, null,
                    "PUBLISHED", 0, null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000008",
                    "GLOBAL", null, null, "MANUAL", null, null,
                    "ARCHIVED", 2, null, null, null, null, null)))
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * 业务目的：替代关系必须拒绝自指且一个旧文档最多有一个当前替代者，防止并发发布覆盖追溯事实。
     */
    @Test
    void replacementConstraintsRejectSelfReferenceAndCompetingReplacement() throws Exception {
        String schema = "knowledge_replacement_constraints";
        migrationFor(schema).migrate();
        String oldId = "10000000-0000-0000-0000-000000000001";

        try (Connection connection = connection()) {
            execute(connection, insertKnowledgeDocument(
                    schema, oldId, "GLOBAL", null, null, "MANUAL", null, null,
                    "PUBLISHED", 2, "2026-07-30T01:00:00Z", "admin", null, null, null));
            execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000002",
                    "GLOBAL", null, null, "MANUAL", null, null,
                    "PUBLISHED", 2, "2026-07-30T02:00:00Z", "admin", null, null, oldId));

            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000003",
                    "GLOBAL", null, null, "MANUAL", null, null,
                    "PUBLISHED", 2, "2026-07-30T02:00:00Z", "admin", null, null, oldId)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertKnowledgeDocument(
                    schema, "10000000-0000-0000-0000-000000000004",
                    "GLOBAL", null, null, "MANUAL", null, null,
                    "PUBLISHED", 2, "2026-07-30T02:00:00Z", "admin", null, null,
                    "10000000-0000-0000-0000-000000000004")))
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * 业务目的：导入批次必须引用真实对象、条目必须引用真实批次/文档且计数非负，防止出现不可追溯导入证据。
     */
    @Test
    void importSchemaEnforcesObjectDocumentForeignKeysAndCounts() throws Exception {
        String schema = "knowledge_import_constraints";
        migrationFor(schema).migrate();

        try (Connection connection = connection()) {
            assertThatThrownBy(() -> execute(connection, insertImportBatch(
                    schema, "20000000-0000-0000-0000-000000000001", "missing-object", 0, -1, 0)))
                    .isInstanceOf(SQLException.class);
            execute(connection, insertStoredObject(schema, "30000000-0000-0000-0000-000000000001", "object-key"));
            execute(connection, insertImportBatch(
                    schema, "20000000-0000-0000-0000-000000000001", "object-key", 0, 1, 0));
            assertThatThrownBy(() -> execute(connection, """
                    insert into %s.knowledge_import_item(
                        id, batch_id, ordinal, entry_name, status, reason_code, message, document_id
                    ) values (
                        '40000000-0000-0000-0000-000000000001',
                        '29999999-9999-9999-9999-999999999999', 0, 'missing.md',
                        'FAILED', 'INVALID_TEXT_ENCODING', '编码不合法', null
                    )
                    """.formatted(schema)))
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * 业务目的：generation 必须引用真实后台任务且最多一个 ACTIVE，投影必须引用真实 generation 和文档，防止半成品成为正式检索事实。
     */
    @Test
    void indexGenerationSchemaEnforcesJobProjectionForeignKeysAndSingleActive() throws Exception {
        String schema = "knowledge_index_constraints";
        migrationFor(schema).migrate();

        try (Connection connection = connection()) {
            execute(connection, insertBackgroundJob(
                    schema, "50000000-0000-0000-0000-000000000001", "KNOWLEDGE_REINDEX"));
            execute(connection, insertBackgroundJob(
                    schema, "50000000-0000-0000-0000-000000000002", "KNOWLEDGE_REINDEX"));
            execute(connection, insertGeneration(
                    schema, "60000000-0000-0000-0000-000000000001",
                    "50000000-0000-0000-0000-000000000001", "ACTIVE"));

            assertThatThrownBy(() -> execute(connection, insertGeneration(
                    schema, "60000000-0000-0000-0000-000000000002",
                    "50000000-0000-0000-0000-000000000002", "ACTIVE")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, insertGeneration(
                    schema, "60000000-0000-0000-0000-000000000003",
                    "59999999-9999-9999-9999-999999999999", "BUILDING")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    insert into %s.knowledge_index_document(
                        generation_id, document_id, source_revision, format, title, body,
                        directory_path, tags, scope_type, project_id, branch_id,
                        source_type, wiki_url, original_filename, curation_note, source_updated_at
                    ) values (
                        '69999999-9999-9999-9999-999999999999',
                        '10000000-0000-0000-0000-000000000001', 1, 'MARKDOWN', '标题', '正文',
                        '', '[]'::jsonb, 'GLOBAL', null, null, 'MANUAL', null, null, null,
                        '2026-07-30T00:00:00Z'
                    )
                    """.formatted(schema)))
                    .isInstanceOf(SQLException.class);
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

    private String insertKnowledgeDocument(
            String schema,
            String id,
            String scopeType,
            String projectId,
            String branchId,
            String sourceType,
            String wikiUrl,
            String originalFilename,
            String status,
            long revision,
            String publishedAt,
            String publishedBy,
            String archivedAt,
            String archivedBy,
            String replacesDocumentId
    ) {
        return """
                insert into %s.knowledge_document(
                    id, format, title, body, directory_path,
                    scope_type, project_id, branch_id,
                    source_type, wiki_url, original_filename, curation_note,
                    status, revision, replaces_document_id,
                    published_at, published_by, archived_at, archived_by,
                    created_at, updated_at, created_by, updated_by
                ) values (
                    %s, 'MARKDOWN', '标题', '正文', '',
                    '%s', %s, %s,
                    '%s', %s, %s, null,
                    '%s', %d, %s,
                    %s, %s, %s, %s,
                    '2026-07-30T00:00:00Z', '2026-07-30T02:00:00Z', 'admin', 'admin'
                )
                """.formatted(
                schema, sqlUuid(id), scopeType, sqlUuid(projectId), sqlUuid(branchId),
                sourceType, sqlText(wikiUrl), sqlText(originalFilename), status, revision,
                sqlUuid(replacesDocumentId), sqlTimestamp(publishedAt), sqlText(publishedBy),
                sqlTimestamp(archivedAt), sqlText(archivedBy));
    }

    private String insertStoredObject(String schema, String id, String objectKey) {
        return """
                insert into %s.stored_object(
                    id, object_key, status, original_filename, content_type, size_bytes, sha256,
                    created_at, updated_at, created_by, updated_by
                ) values (
                    '%s', '%s', 'AVAILABLE', 'knowledge.zip', 'application/zip', 1,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'admin', 'admin'
                )
                """.formatted(schema, id, objectKey);
    }

    private String insertImportBatch(
            String schema, String id, String objectKey, int succeeded, int failed, int ignored) {
        return """
                insert into %s.knowledge_import_batch(
                    id, object_key, original_filename, scope_type, project_id, branch_id,
                    directory_prefix, status, succeeded_count, failed_count, ignored_count,
                    created_at, updated_at, created_by, updated_by
                ) values (
                    '%s', '%s', 'knowledge.zip', 'GLOBAL', null, null,
                    '', 'FAILED', %d, %d, %d,
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'admin', 'admin'
                )
                """.formatted(schema, id, objectKey, succeeded, failed, ignored);
    }

    private String insertBackgroundJob(String schema, String id, String type) {
        return """
                insert into %s.background_job(
                    id, job_type, status, progress,
                    created_at, updated_at, created_by, updated_by
                ) values (
                    '%s', '%s', 'PENDING', 0,
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'admin', 'admin'
                )
                """.formatted(schema, id, type);
    }

    private String insertGeneration(String schema, String id, String jobId, String status) {
        return """
                insert into %s.knowledge_index_generation(
                    id, job_id, status, document_count, created_at, activated_at
                ) values (
                    '%s', '%s', '%s', 0,
                    '2026-07-30T00:00:00Z', %s
                )
                """.formatted(
                schema, id, jobId, status,
                status.equals("ACTIVE") ? "'2026-07-30T01:00:00Z'" : "null");
    }

    private String sqlUuid(String value) {
        return value == null ? "null" : "'" + value + "'";
    }

    private String sqlText(String value) {
        return value == null ? "null" : "'" + value.replace("'", "''") + "'";
    }

    private String sqlTimestamp(String value) {
        return sqlText(value);
    }
}
