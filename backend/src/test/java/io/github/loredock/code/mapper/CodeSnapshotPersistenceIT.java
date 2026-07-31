package io.github.loredock.code.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.code.model.entity.CodeIndexGenerationEntity;
import io.github.loredock.code.model.entity.CodeSnapshotEntity;
import io.github.loredock.code.model.request.AdminCodeSnapshotQuery;
import io.github.loredock.code.model.result.CodeSnapshotAdminPage;
import io.github.loredock.code.service.CodeSnapshotDataService;
import io.github.loredock.persistence.MybatisMapperFactory;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
        Long projectId = 8000000000000000172L;
        Long branchId = 8000000000000000173L;
        Long snapshotId = 8000000000000000174L;
        Long jobId = 8000000000000000175L;
        Long generationId = 8000000000000000176L;
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
     * 业务目的：管理分页在创建时间相同的记录间必须按 Long 正序稳定切页，并严格应用项目与分支筛选。
     */
    @Test
    void adminPageUsesStableCreatedAtAndIdOrderingWithinScope() {
        Long projectId = 8000000000000000177L;
        Long branchId = 8000000000000000178L;
        Long jobId = 8000000000000000179L;
        Long first = 4460912964485513218L;
        Long second = 4460912964485513219L;
        Long newest = 4460912964485513220L;
        String firstObject = "object-" + first;
        String secondObject = "object-" + second;
        String newestObject = "object-" + newest;
        seedDependencies(projectId, branchId, jobId, firstObject);
        insertStoredObject(secondObject);
        insertStoredObject(newestObject);
        insertCandidate(second, projectId, branchId, secondObject, NOW.minusSeconds(10));
        insertCandidate(first, projectId, branchId, firstObject, NOW.minusSeconds(10));
        insertCandidate(newest, projectId, branchId, newestObject, NOW);

        CodeSnapshotDataService repository = new CodeSnapshotDataService(snapshots);
        CodeSnapshotAdminPage page0 = repository.listAdmin(new AdminCodeSnapshotQuery(projectId, branchId, 0, 2));
        CodeSnapshotAdminPage page1 = repository.listAdmin(new AdminCodeSnapshotQuery(projectId, branchId, 1, 2));

        assertThat(page0.items()).extracting(item -> item.snapshotId()).containsExactly(newest, first);
        assertThat(page1.items()).extracting(item -> item.snapshotId()).containsExactly(second);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
    }

    private void insertCandidate(Long id, Long projectId, Long branchId, String objectKey, Instant createdAt) {
        snapshots.insert(CodeSnapshotEntity.builder()
                .id(id).projectId(projectId).branchId(branchId).commitHash("abcdef1")
                .inputObjectKey(objectKey).status("CANDIDATE").indexedFileCount(0L).ignoredFileCount(0L)
                .createdAt(createdAt).updatedAt(createdAt).createdBy("tester").updatedBy("tester").build());
    }

    private void seedDependencies(Long projectId, Long branchId, Long jobId, String objectKey) {
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
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?, 'AVAILABLE', 'code.zip', 'application/zip', 4, ?, ?, ?, 'tester', 'tester')
                """, objectKey, "a".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
