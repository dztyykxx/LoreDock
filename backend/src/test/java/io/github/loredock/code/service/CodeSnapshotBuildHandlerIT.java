package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.CodeSnapshotArchiveInvalidException;
import io.github.loredock.code.mapper.ActiveCodeSnapshotMapper;
import io.github.loredock.code.mapper.CodeIndexGenerationMapper;
import io.github.loredock.code.mapper.CodeSnapshotLifecycleMapper;
import io.github.loredock.code.mapper.CodeSnapshotMapper;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.CodeArchiveEntry;
import io.github.loredock.code.service.archive.CommonsCompressCodeArchiveReader;
import io.github.loredock.code.service.archive.DefaultCodeFileSelector;
import io.github.loredock.code.service.index.FilesystemCodeGenerationPublisher;
import io.github.loredock.code.service.index.LuceneIndexHandleRegistry;
import io.github.loredock.job.mapper.BackgroundJobMapper;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.job.api.JobService;
import io.github.loredock.persistence.MybatisMapperFactory;
import io.github.loredock.support.TestIds;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.unit.DataSize;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CodeSnapshotBuildHandlerIT {

    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_code_build_test").withUsername("loredock").withPassword("loredock_test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CodeSnapshotMapper snapshotMapper;
    private static CodeIndexGenerationMapper generationMapper;
    private static CodeSnapshotLifecycleMapper lifecycleMapper;
    private static BackgroundJobMapper jobMapper;
    private static ActiveCodeSnapshotMapper activeSnapshotMapper;

    @TempDir
    Path temporaryRoot;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        snapshotMapper = MybatisMapperFactory.create(dataSource, CodeSnapshotMapper.class);
        generationMapper = MybatisMapperFactory.create(dataSource, CodeIndexGenerationMapper.class);
        lifecycleMapper = MybatisMapperFactory.create(dataSource, CodeSnapshotLifecycleMapper.class);
        jobMapper = MybatisMapperFactory.create(dataSource, BackgroundJobMapper.class);
        activeSnapshotMapper = MybatisMapperFactory.create(dataSource, ActiveCodeSnapshotMapper.class);
    }

    /**
     * 业务目的：首次构建只有在 Lucene 发布成功后才能在同一短事务激活快照与 generation，并记录文件计数、索引时间、进度和心跳。
     */
    @Test
    void firstSuccessfulBuildAtomicallyActivatesSnapshotAndGeneration() throws Exception {
        Fixture fixture = seed("abcdef1", null);
        RecordingContext context = new RecordingContext(fixture);

        handler().execute(context);

        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class, fixture.snapshotId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select indexed_file_count from code_snapshot where id=?", Long.class,
                fixture.snapshotId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select ignored_file_count from code_snapshot where id=?", Long.class,
                fixture.snapshotId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from code_index_generation where snapshot_id=?", String.class,
                fixture.snapshotId)).isEqualTo("ACTIVE");
        assertThat(context.progress).isGreaterThanOrEqualTo(95);
        assertThat(context.heartbeats).isPositive();
    }

    /**
     * 业务目的：不同 commit 的新候选成功后必须在一个事务中退休旧快照及 generation，并把旧快照记录为变化提示前驱。
     */
    @Test
    void newerCommitRetiresPreviousSnapshotAndGenerationInSameTransaction() throws Exception {
        Fixture first = seed("abcde11", null);
        handler().execute(new RecordingContext(first));
        finishJob(first.jobId);
        Fixture second = seed("abcde22", first.branchId);

        handler().execute(new RecordingContext(second));

        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class, first.snapshotId))
                .isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("select status from code_index_generation where snapshot_id=?", String.class,
                first.snapshotId)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class, second.snapshotId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select previous_snapshot_id from code_snapshot where id=?", Long.class,
                second.snapshotId)).isEqualTo(first.snapshotId);
    }

    /**
     * 业务目的：活动切换数据库提交失败时旧快照必须保持完整活动，新候选与 generation 终结为失败而不能产生新旧混配。
     */
    @Test
    void activationDatabaseFailurePreservesOldActiveEntryAndFailsCandidate() throws Exception {
        Fixture first = seed("abcde31", null);
        handler().execute(new RecordingContext(first));
        finishJob(first.jobId);
        Fixture second = seed("abcde32", first.branchId);
        jdbc.execute("""
                create function reject_snapshot_activation() returns trigger language plpgsql as $$
                begin raise exception 'simulated activation failure'; end $$
                """);
        jdbc.execute("""
                create trigger reject_snapshot_activation before update on code_snapshot
                for each row when (new.commit_hash = 'abcde32' and new.status = 'ACTIVE')
                execute function reject_snapshot_activation()
                """);
        try {
            assertThatThrownBy(() -> handler().execute(new RecordingContext(second)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class,
                    first.snapshotId)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class,
                    second.snapshotId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select status from code_index_generation where snapshot_id=?", String.class,
                    second.snapshotId)).isEqualTo("FAILED");
        } finally {
            jdbc.execute("drop trigger reject_snapshot_activation on code_snapshot");
            jdbc.execute("drop function reject_snapshot_activation()");
        }
    }

    /**
     * 业务目的：归档读取或文件选择失败必须把候选与 BUILDING generation 标记失败，且已有活动快照不受影响。
     */
    @Test
    void archiveFailureMarksCandidateAndGenerationFailedWithoutActivation() {
        Fixture fixture = seed("abcde41", null);
        CommonsCompressCodeArchiveReader failingArchive = mock(CommonsCompressCodeArchiveReader.class);
        doThrow(new CodeSnapshotArchiveInvalidException()).when(failingArchive).read(any(), any(), any());

        assertThatThrownBy(() -> handler(failingArchive).execute(new RecordingContext(fixture)))
                .isInstanceOf(CodeSnapshotArchiveInvalidException.class);
        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class, fixture.snapshotId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from code_index_generation where snapshot_id=?", String.class,
                fixture.snapshotId)).isEqualTo("FAILED");
    }

    /**
     * 业务目的：活动快照重建成功必须保持同一快照和 commit，只原子退休旧 generation 并激活新 generation。
     */
    @Test
    void successfulReindexReplacesGenerationWithoutReplacingSnapshot() throws Exception {
        Fixture build = seed("abcde51", null);
        handler().execute(new RecordingContext(build));
        finishJob(build.jobId);
        Fixture reindex = seedReindex(build);

        reindexHandler(defaultArchive()).execute(new RecordingContext(reindex));

        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class, build.snapshotId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select commit_hash from code_snapshot where id=?", String.class,
                build.snapshotId)).isEqualTo("abcde51");
        assertThat(jdbc.queryForObject("select count(*) from code_index_generation where snapshot_id=? and status='ACTIVE'",
                Integer.class, build.snapshotId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from code_index_generation where snapshot_id=? and status='RETIRED'",
                Integer.class, build.snapshotId)).isEqualTo(1);
    }

    /**
     * 业务目的：重建失败只能把新 generation 标记失败，原活动快照与旧 generation 必须继续可查询。
     */
    @Test
    void failedReindexKeepsOriginalSnapshotAndGenerationActive() throws Exception {
        Fixture build = seed("abcde61", null);
        handler().execute(new RecordingContext(build));
        finishJob(build.jobId);
        Fixture reindex = seedReindex(build);
        CommonsCompressCodeArchiveReader failing = mock(CommonsCompressCodeArchiveReader.class);
        doThrow(new CodeSnapshotArchiveInvalidException()).when(failing).read(any(), any(), any());

        assertThatThrownBy(() -> reindexHandler(failing).execute(new RecordingContext(reindex)))
                .isInstanceOf(CodeSnapshotArchiveInvalidException.class);
        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class, build.snapshotId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from code_index_generation where snapshot_id=? and status='ACTIVE'",
                Integer.class, build.snapshotId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from code_index_generation where snapshot_id=? and status='FAILED'",
                Integer.class, build.snapshotId)).isEqualTo(1);
    }

    /**
     * 业务目的：通用任务恢复把中断任务终结后，代码恢复必须把关联候选和 BUILDING generation 标记失败且保留旧活动入口。
     */
    @Test
    void interruptedBuildRecoveryFailsCandidateAndKeepsOldActiveGeneration() throws Exception {
        Fixture active = seed("abcde71", null);
        handler().execute(new RecordingContext(active));
        finishJob(active.jobId);
        Fixture interrupted = seed("abcde72", active.branchId);
        Long generationId = TestIds.next();
        jdbc.update("""
                insert into code_index_generation(id, snapshot_id, job_id, status, document_count, created_at)
                values (?, ?, ?, 'BUILDING', 0, ?)
                """, generationId, interrupted.snapshotId, interrupted.jobId, Timestamp.from(NOW));
        jdbc.update("""
                update background_job set status='FAILED', error_code='PROCESS_INTERRUPTED',
                    error_message='进程中断', finished_at=? where id=?
                """, Timestamp.from(NOW), interrupted.jobId);

        var repository = new CodeSnapshotRecoveryService(snapshotMapper, generationMapper, jobId -> {
            var job = jobMapper.selectById(jobId);
            return job == null || JobStatus.valueOf(job.getStatus()).terminal();
        });
        var activeIds = repository.reconcileInterruptedBuilds();

        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class,
                interrupted.snapshotId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from code_index_generation where id=?", String.class,
                generationId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class,
                active.snapshotId)).isEqualTo("ACTIVE");
        Long activeGenerationId = jdbc.queryForObject(
                "select id from code_index_generation where snapshot_id=? and status='ACTIVE'",
                Long.class, active.snapshotId);
        assertThat(activeIds).contains(activeGenerationId).doesNotContain(generationId);
    }

    /**
     * 业务目的：真实 PostgreSQL 活动查询必须同时约束 ACTIVE snapshot/generation，后续候选存在时仍固定返回旧活动 commit。
     */
    @Test
    void activeRepositoryReturnsOnlyMatchedActiveSnapshotAndGeneration() throws Exception {
        Fixture active = seed("abcde81", null);
        handler().execute(new RecordingContext(active));
        finishJob(active.jobId);
        seed("abcde82", active.branchId);

        ActiveCodeSnapshotDescriptor descriptor = new ActiveCodeSnapshotDataService(activeSnapshotMapper)
                .findActive(active.branchId).orElseThrow();

        assertThat(descriptor.snapshotId()).isEqualTo(active.snapshotId);
        assertThat(descriptor.commit()).isEqualTo("abcde81");
        assertThat(descriptor.changeHint()).isEqualTo(CodeSnapshotChangeHint.INITIAL);
    }

    private CodeSnapshotBuildJobHandler handler() {
        return handler(defaultArchive());
    }

    private CommonsCompressCodeArchiveReader defaultArchive() {
        CommonsCompressCodeArchiveReader archive = mock(CommonsCompressCodeArchiveReader.class);
        doAnswer(invocation -> {
            CommonsCompressCodeArchiveReader.EntryConsumer consumer = invocation.getArgument(2);
            accept(consumer, "src/A.java", "class A {}\n");
            accept(consumer, "src/B.vue", "<template>B</template>\n");
            accept(consumer, ".env", "TOKEN=secret\n");
            return null;
        }).when(archive).read(any(), any(), any());
        return archive;
    }

    private CodeSnapshotBuildJobHandler handler(CommonsCompressCodeArchiveReader archive) {
        CodeSnapshotProperties properties = properties();
        var lifecycle = new CodeSnapshotLifecycleService(
                snapshotMapper, generationMapper, lifecycleMapper,
                new DataSourceTransactionManager(dataSource), Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        var builder = new CodeSnapshotGenerationBuilder(
                archive, new DefaultCodeFileSelector(properties), new FilesystemCodeGenerationPublisher(properties));
        return new CodeSnapshotBuildJobHandler(
                new io.github.loredock.code.service.CodeSnapshotDataService(snapshotMapper),
                lifecycle, builder, mock(LuceneIndexHandleRegistry.class));
    }

    private CodeSnapshotReindexJobHandler reindexHandler(CommonsCompressCodeArchiveReader archive) {
        CodeSnapshotProperties properties = properties();
        var lifecycle = new CodeSnapshotLifecycleService(
                snapshotMapper, generationMapper, lifecycleMapper,
                new DataSourceTransactionManager(dataSource), Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        var builder = new CodeSnapshotGenerationBuilder(
                archive, new DefaultCodeFileSelector(properties), new FilesystemCodeGenerationPublisher(properties));
        return new CodeSnapshotReindexJobHandler(
                new io.github.loredock.code.service.CodeSnapshotDataService(snapshotMapper),
                lifecycle, builder, mock(LuceneIndexHandleRegistry.class));
    }

    private void accept(CommonsCompressCodeArchiveReader.EntryConsumer consumer, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            consumer.accept(new CodeArchiveEntry(path, bytes.length, bytes.length), new ByteArrayInputStream(bytes));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private Fixture seed(String commit, Long existingBranchId) {
        Long projectId = TestIds.next();
        Long branchId = existingBranchId == null ? TestIds.next() : existingBranchId;
        Long snapshotId = TestIds.next();
        Long jobId = TestIds.next();
        String objectKey = java.util.UUID.randomUUID().toString();
        if (existingBranchId == null) {
            jdbc.update("""
                    insert into project_space(id, identifier, name, description, technology_stack, status,
                        created_at, updated_at, created_by, updated_by)
                    values (?, ?, 'Build', '', '', 'ENABLED', ?, ?, 'test', 'test')
                    """, projectId, "build-" + projectId,
                    Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("""
                    insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                    values (?, ?, 'main', ?, ?, 'test', 'test')
                    """, branchId, projectId, Timestamp.from(NOW), Timestamp.from(NOW));
        } else {
            projectId = jdbc.queryForObject("select project_id from project_branch where id=?", Long.class, branchId);
        }
        jdbc.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?, 'AVAILABLE', 'code.zip', 'application/zip', 4, ?, ?, ?, 'test', 'test')
                """, objectKey, "a".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into code_snapshot(id, project_id, branch_id, commit_hash, input_object_key, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, 'test', 'test')
                """, snapshotId, projectId, branchId, commit, objectKey, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into background_job(id, job_type, status, progress, input_object_key,
                    project_id, branch_id, snapshot_id, created_at, updated_at, created_by, updated_by)
                values (?, 'CODE_SNAPSHOT_BUILD', 'RUNNING', 1, ?, ?, ?, ?, ?, ?, 'test', 'test')
                """, jobId, objectKey, projectId, branchId, snapshotId, Timestamp.from(NOW), Timestamp.from(NOW));
        return new Fixture(projectId, branchId, snapshotId, jobId, objectKey);
    }

    private void finishJob(Long jobId) {
        jdbc.update("update background_job set status='SUCCEEDED', progress=100, finished_at=? where id=?",
                Timestamp.from(NOW), jobId);
    }

    private Fixture seedReindex(Fixture active) {
        Long jobId = TestIds.next();
        jdbc.update("""
                insert into background_job(id, job_type, status, progress, input_object_key,
                    project_id, branch_id, snapshot_id, created_at, updated_at, created_by, updated_by)
                values (?, 'CODE_SNAPSHOT_REINDEX', 'RUNNING', 1, ?, ?, ?, ?, ?, ?, 'test', 'test')
                """, jobId, active.objectKey, active.projectId, active.branchId, active.snapshotId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return new Fixture(active.projectId, active.branchId, active.snapshotId, jobId, active.objectKey);
    }

    private CodeSnapshotProperties properties() {
        return new CodeSnapshotProperties(
                DataSize.ofMegabytes(100), 100, DataSize.ofMegabytes(10), DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(100), BigDecimal.valueOf(100), 400,
                temporaryRoot.resolve("work"), temporaryRoot.resolve("index"));
    }

    private record Fixture(Long projectId, Long branchId, Long snapshotId, Long jobId, String objectKey) {
    }

    private static final class RecordingContext implements JobService.ExecutionContext {
        private final Fixture fixture;
        private int progress;
        private int heartbeats;

        private RecordingContext(Fixture fixture) { this.fixture = fixture; }
        @Override public Long jobId() { return fixture.jobId; }
        @Override public String inputObjectKey() { return fixture.objectKey; }
        @Override public Long projectId() { return fixture.projectId; }
        @Override public Long branchId() { return fixture.branchId; }
        @Override public Long snapshotId() { return fixture.snapshotId; }
        @Override public void updateProgress(int progress) { this.progress = progress; }
        @Override public void heartbeat() { heartbeats++; }
    }
}
