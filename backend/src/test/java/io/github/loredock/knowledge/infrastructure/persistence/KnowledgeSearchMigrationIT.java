package io.github.loredock.knowledge.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
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
class KnowledgeSearchMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_knowledge_search_migration_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @BeforeAll
    static void installVectorInSharedExtensionSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector with schema public");
        }
    }

    /**
     * 业务目的：空库、V1 与 V4 现有数据库都必须仅追加升级到 V5，重复启动不得重建检索表或改写历史数据。
     */
    @Test
    void emptyVersionOneAndVersionFourDatabasesUpgradeOnceToV5() throws Exception {
        Flyway empty = migrationFor("search_empty");
        assertThat(empty.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(empty.migrate().migrationsExecuted).isZero();

        migrateTo("search_upgrade_v1", "1");
        assertThat(migrationFor("search_upgrade_v1").migrate().migrationsExecuted).isEqualTo(4);

        migrateTo("search_upgrade_v4", "4");
        assertThat(migrationFor("search_upgrade_v4").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = connection()) {
            for (String schema : new String[]{"search_empty", "search_upgrade_v1", "search_upgrade_v4"}) {
                assertThat(exists(connection, schema, "knowledge_search_generation")).as(schema).isTrue();
                assertThat(exists(connection, schema, "knowledge_search_chunk")).as(schema).isTrue();
            }
        }
        System.out.println("测试证据：场景=V5追加迁移，空库执行=5，V1升级=4，V4升级=1，重复迁移=0");
    }

    /**
     * 业务目的：升级前已激活的 T3 generation 没有检索元数据时仍必须可用于知识浏览，且不能被误认成可搜索 generation。
     */
    @Test
    void oldActiveGenerationRemainsBrowsableWithoutImplicitSearchMetadata() throws Exception {
        String schema = "search_legacy_active";
        migrateTo(schema, "4");
        try (Connection connection = connection()) {
            seedProjection(connection, schema, "GLOBAL", null, null);
        }

        assertThat(migrationFor(schema).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = connection()) {
            assertThat(queryLong(connection, "select count(*) from " + schema
                    + ".knowledge_index_document where title='Legacy published knowledge'")).isEqualTo(1);
            assertThat(queryLong(connection, "select count(*) from " + schema
                    + ".knowledge_search_generation")).isZero();
            assertThat(queryLong(connection, "select count(*) from " + schema
                    + ".knowledge_index_generation where status='ACTIVE'")).isEqualTo(1);
        }
        System.out.println("测试证据：场景=旧ACTIVE升级，浏览投影数=1，搜索元数据数=0，ACTIVE数=1");
    }

    /**
     * 业务目的：generation 一对一、分块复合键/FK、三类范围、offset、计数和 512 维必须由 PostgreSQL 兜底，
     * 防止绕过应用层写入跨范围、悬空或不可计算的候选数据。
     */
    @Test
    void v5ConstraintsAndIndexesProtectSearchCandidateFacts() throws Exception {
        String schema = "search_constraints";
        migrationFor(schema).migrate();
        try (Connection connection = connection()) {
            seedProjection(connection, schema, "BRANCH",
                    "11111111-1111-1111-1111-111111111111",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            insertSearchGeneration(connection, schema);
            execute(connection, chunkSql(schema, 0, 0, 12, "BRANCH",
                    "11111111-1111-1111-1111-111111111111",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", vector(512)));

            assertThatThrownBy(() -> insertSearchGeneration(connection, schema))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, chunkSql(schema, 0, 0, 12, "BRANCH",
                    "11111111-1111-1111-1111-111111111111",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", vector(512))))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, chunkSql(schema, 1, 12, 12, "BRANCH",
                    "11111111-1111-1111-1111-111111111111",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", vector(512))))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, chunkSql(schema, 1, 12, 20, "PROJECT",
                    "11111111-1111-1111-1111-111111111111",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", vector(512))))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, chunkSql(schema, 1, 12, 20, "BRANCH",
                    "11111111-1111-1111-1111-111111111111",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", vector(511))))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, chunkSqlForDocument(
                    schema, "99999999-9999-9999-9999-999999999999", 1, vector(512))))
                    .isInstanceOf(SQLException.class);

            assertThat(indexExists(connection, schema, "idx_knowledge_search_chunk_scope")).isTrue();
            assertThat(indexExists(connection, schema, "idx_knowledge_search_chunk_fulltext")).isTrue();
            assertThat(indexExists(connection, schema, "idx_knowledge_search_chunk_tags")).isTrue();
            assertThat(queryText(connection, "select pg_typeof(search_vector)::text from " + schema
                    + ".knowledge_search_chunk limit 1")).isEqualTo("tsvector");
            assertThat(queryLong(connection, "select vector_dims(embedding) from " + schema
                    + ".knowledge_search_chunk limit 1")).isEqualTo(512);
        }
        System.out.println("测试证据：场景=V5数据库约束，合法分块数=1，维度=512，范围/全文/标签索引均存在");
    }

    private void migrateTo(String schema, String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
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

    private void seedProjection(
            Connection connection,
            String schema,
            String scopeType,
            String projectId,
            String branchId
    ) throws SQLException {
        execute(connection, """
                insert into %s.project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values ('11111111-1111-1111-1111-111111111111', 'search-project', 'Search Project', '', '',
                    'ENABLED', '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'main',
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.knowledge_document(id, format, title, body, directory_path, scope_type,
                    project_id, branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values ('22222222-2222-2222-2222-222222222222', 'MARKDOWN', 'Legacy published knowledge',
                    'legacy body', '', '%s', %s, %s, 'MANUAL', 'PUBLISHED', 1,
                    '2026-07-30T00:00:00Z', 'publisher', '2026-07-30T00:00:00Z',
                    '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema, scopeType, nullableUuid(projectId), nullableUuid(branchId)));
        execute(connection, """
                insert into %s.background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values ('33333333-3333-3333-3333-333333333333', 'KNOWLEDGE_REINDEX', 'SUCCEEDED', 100,
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z', 'test', 'test')
                """.formatted(schema));
        execute(connection, """
                insert into %s.knowledge_index_generation(id, job_id, status, document_count, created_at, activated_at)
                values ('44444444-4444-4444-4444-444444444444',
                    '33333333-3333-3333-3333-333333333333', 'ACTIVE', 1,
                    '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z')
                """.formatted(schema));
        execute(connection, """
                insert into %s.knowledge_index_document(generation_id, document_id, source_revision, format,
                    title, body, directory_path, tags, scope_type, project_id, branch_id, source_type,
                    source_updated_at)
                values ('44444444-4444-4444-4444-444444444444',
                    '22222222-2222-2222-2222-222222222222', 1, 'MARKDOWN', 'Legacy published knowledge',
                    'legacy body', '', '["search"]'::jsonb, '%s', %s, %s, 'MANUAL', '2026-07-30T00:00:00Z')
                """.formatted(schema, scopeType, nullableUuid(projectId), nullableUuid(branchId)));
    }

    private void insertSearchGeneration(Connection connection, String schema) throws SQLException {
        execute(connection, """
                insert into %s.knowledge_search_generation(generation_id, model_id, model_checksum,
                    vector_dimension, chunk_strategy_version, fusion_config_version, document_count,
                    chunk_count, created_at)
                values ('44444444-4444-4444-4444-444444444444', 'BAAI/bge-small-zh-v1.5', '%s', 512,
                    'cjk-v1', 'rrf-v1', 1, 1, '2026-07-30T00:00:00Z')
                """.formatted(schema, "a".repeat(64)));
    }

    private String chunkSql(
            String schema,
            int chunkNo,
            int startOffset,
            int endOffset,
            String scopeType,
            String projectId,
            String branchId,
            String vector
    ) {
        return """
                insert into %s.knowledge_search_chunk(generation_id, document_id, chunk_no,
                    start_offset, end_offset, content, title_terms, tag_terms, content_terms, search_vector,
                    embedding, scope_type, project_id, branch_id, format, source_type, normalized_tags,
                    source_updated_at)
                values ('44444444-4444-4444-4444-444444444444',
                    '22222222-2222-2222-2222-222222222222', %d, %d, %d, '检索分块正文',
                    'legacy published knowledge', 'search', '检索 分块 正文',
                    setweight(to_tsvector('simple', 'legacy published knowledge'), 'A') ||
                    setweight(to_tsvector('simple', 'search'), 'B') ||
                    setweight(to_tsvector('simple', '检索 分块 正文'), 'C'),
                    '%s'::vector, '%s', %s, %s, 'MARKDOWN', 'MANUAL', array['search'],
                    '2026-07-30T00:00:00Z')
                """.formatted(schema, chunkNo, startOffset, endOffset, vector, scopeType,
                nullableUuid(projectId), nullableUuid(branchId));
    }

    private String chunkSqlForDocument(String schema, String documentId, int chunkNo, String vector) {
        return chunkSql(schema, chunkNo, 0, 12, "GLOBAL", null, null, vector)
                .replace("22222222-2222-2222-2222-222222222222", documentId);
    }

    private String vector(int dimension) {
        return "[" + String.join(",", java.util.Collections.nCopies(dimension, "0.01")) + "]";
    }

    private String nullableUuid(String value) {
        return value == null ? "null" : "'" + value + "'";
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

    private boolean indexExists(Connection connection, String schema, String index) throws SQLException {
        return queryLong(connection, "select count(*) from pg_indexes where schemaname='" + schema
                + "' and indexname='" + index + "'") == 1;
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryText(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
