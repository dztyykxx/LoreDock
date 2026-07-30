package io.github.loredock.code.infrastructure.persistence;

import io.github.loredock.code.application.AdminCodeSnapshotQuery;
import io.github.loredock.code.application.CodeSnapshotAdminPage;
import io.github.loredock.persistence.MybatisMapperFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CodeSnapshotPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-07-30T04:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_code_mapping_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    private static JdbcTemplate jdbc;
    private static CodeSnapshotMapper snapshots;
    private static CodeIndexGenerationMapper generations;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        snapshots = MybatisMapperFactory.create(dataSource, CodeSnapshotMapper.class);
        generations = MybatisMapperFactory.create(dataSource, CodeIndexGenerationMapper.class);
    }

    /**
     * 业务目的：V4 快照和 generation 必须通过 MyBatis-Plus 逐字段完整往返，防止 commit、范围、计数或激活时间因隐式映射丢失。
     */
    @Test
    void explicitEntitiesRoundTripEveryCodeSnapshotColumn() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        String objectKey = "object-" + snapshotId;
        seedDependencies(projectId, branchId, jobId, objectKey);

        CodeSnapshotEntity snapshot = CodeSnapshotEntity.builder()
                .id(snapshotId)
                .projectId(projectId)
                .branchId(branchId)
                .commitHash("abcdef123456")
                .inputObjectKey(objectKey)
                .status("ACTIVE")
                .indexedFileCount(12L)
                .ignoredFileCount(3L)
                .indexedAt(NOW)
                .createdAt(NOW.minusSeconds(60))
                .updatedAt(NOW)
                .createdBy("tester")
                .updatedBy("tester")
                .build();
        assertThat(snapshots.insert(snapshot)).isEqualTo(1);

        jdbc.update("""
                update background_job set snapshot_id = ? where id = ?
                """, snapshotId, jobId);
        CodeIndexGenerationEntity generation = CodeIndexGenerationEntity.builder()
                .id(generationId)
                .snapshotId(snapshotId)
                .jobId(jobId)
                .status("ACTIVE")
                .documentCount(12L)
                .createdAt(NOW.minusSeconds(30))
                .activatedAt(NOW)
                .build();
        assertThat(generations.insert(generation)).isEqualTo(1);

        CodeSnapshotEntity loadedSnapshot = snapshots.selectById(snapshotId);
        assertThat(loadedSnapshot.getProjectId()).isEqualTo(projectId);
        assertThat(loadedSnapshot.getBranchId()).isEqualTo(branchId);
        assertThat(loadedSnapshot.getCommitHash()).isEqualTo("abcdef123456");
        assertThat(loadedSnapshot.getInputObjectKey()).isEqualTo(objectKey);
        assertThat(loadedSnapshot.getStatus()).isEqualTo("ACTIVE");
        assertThat(loadedSnapshot.getIndexedFileCount()).isEqualTo(12L);
        assertThat(loadedSnapshot.getIgnoredFileCount()).isEqualTo(3L);
        assertThat(loadedSnapshot.getIndexedAt()).isEqualTo(NOW);
        assertThat(generations.selectById(generationId).getDocumentCount()).isEqualTo(12L);
        assertThat(generations.selectById(generationId).getActivatedAt()).isEqualTo(NOW);
    }

    /**
     * 业务目的：管理分页在创建时间相同的记录间必须按 UUID 正序稳定切页，并严格应用项目与分支筛选。
     */
    @Test
    void adminPageUsesStableCreatedAtAndIdOrderingWithinScope() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID first = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID newest = UUID.fromString("10000000-0000-0000-0000-000000000003");
        String firstObject = "object-" + first;
        String secondObject = "object-" + second;
        String newestObject = "object-" + newest;
        seedDependencies(projectId, branchId, jobId, firstObject);
        insertStoredObject(secondObject);
        insertStoredObject(newestObject);
        insertCandidate(second, projectId, branchId, secondObject, NOW.minusSeconds(10));
        insertCandidate(first, projectId, branchId, firstObject, NOW.minusSeconds(10));
        insertCandidate(newest, projectId, branchId, newestObject, NOW);

        MybatisPlusCodeSnapshotRepository repository = new MybatisPlusCodeSnapshotRepository(snapshots);
        CodeSnapshotAdminPage page0 = repository.listAdmin(new AdminCodeSnapshotQuery(projectId, branchId, 0, 2));
        CodeSnapshotAdminPage page1 = repository.listAdmin(new AdminCodeSnapshotQuery(projectId, branchId, 1, 2));

        assertThat(page0.items()).extracting(item -> item.snapshotId()).containsExactly(newest, first);
        assertThat(page1.items()).extracting(item -> item.snapshotId()).containsExactly(second);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
    }

    private void insertCandidate(UUID id, UUID projectId, UUID branchId, String objectKey, Instant createdAt) {
        snapshots.insert(CodeSnapshotEntity.builder()
                .id(id).projectId(projectId).branchId(branchId).commitHash("abcdef1")
                .inputObjectKey(objectKey).status("CANDIDATE").indexedFileCount(0L).ignoredFileCount(0L)
                .createdAt(createdAt).updatedAt(createdAt).createdBy("tester").updatedBy("tester").build());
    }

    private void seedDependencies(UUID projectId, UUID branchId, UUID jobId, String objectKey) {
        jdbc.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, 'Mapping', '', '', 'ENABLED', ?, ?, 'tester', 'tester')
                """, projectId, "mapping-" + projectId.toString().substring(0, 8),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', ?, ?, 'tester', 'tester')
                """, branchId, projectId, Timestamp.from(NOW), Timestamp.from(NOW));
        insertStoredObject(objectKey);
        jdbc.update("""
                insert into background_job(id, job_type, status, progress, project_id, branch_id,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'CODE_SNAPSHOT_BUILD', 'SUCCEEDED', 100, ?, ?, ?, ?, 'tester', 'tester')
                """, jobId, projectId, branchId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertStoredObject(String objectKey) {
        jdbc.update("""
                insert into stored_object(id, object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'AVAILABLE', 'code.zip', 'application/zip', 4, ?, ?, ?, 'tester', 'tester')
                """, UUID.randomUUID(), objectKey, "a".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
