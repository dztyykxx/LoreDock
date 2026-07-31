package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.code.config.CodeSnapshotJobTypes;
import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.ProjectDisabledException;
import io.github.loredock.code.mapper.CodeSnapshotMapper;
import io.github.loredock.code.model.command.UploadCodeSnapshotCommand;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.model.result.CodeSnapshotUpload;
import io.github.loredock.code.service.archive.CodeSnapshotUploadValidator;
import io.github.loredock.code.service.storage.ObjectStorageCodeSnapshotCompensation;
import io.github.loredock.job.config.JobProperties;
import io.github.loredock.job.mapper.BackgroundJobMapper;
import io.github.loredock.job.service.JobFailureClassifier;
import io.github.loredock.job.service.JobHandler;
import io.github.loredock.job.service.PersistentBackgroundJobService;
import io.github.loredock.persistence.MybatisMapperFactory;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.model.enums.ProjectStatus;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.storage.model.result.ObjectMetadata;
import io.github.loredock.storage.model.result.StoredObject;
import io.github.loredock.storage.service.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.unit.DataSize;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CodeSnapshotUploadServiceIT {

    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_code_upload_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static CodeSnapshotMapper snapshotMapper;
    private static BackgroundJobMapper jobMapper;

    private Long projectId;
    private Long branchId;
    private TestObjectStorage storage;
    private PersistentBackgroundJobService jobs;
    private CountDownLatch release;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        snapshotMapper = MybatisMapperFactory.create(dataSource, CodeSnapshotMapper.class);
        jobMapper = MybatisMapperFactory.create(dataSource, BackgroundJobMapper.class);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("delete from code_index_generation");
        jdbc.update("delete from background_job");
        jdbc.update("delete from code_snapshot");
        jdbc.update("delete from stored_object");
        jdbc.update("delete from project_branch");
        jdbc.update("delete from project_space");
        projectId = 8000000000000000154L;
        branchId = 8000000000000000155L;
        seedProject(ProjectStatus.ENABLED);
        storage = new TestObjectStorage(jdbc);
        release = new CountDownLatch(1);
        jobs = jobs(release);
    }

    @AfterEach
    void closeJobs() {
        release.countDown();
        jobs.close();
    }

    /**
     * 业务目的：有效上传只受理为 CANDIDATE 与可轮询任务，202 模型不得让候选提前进入普通活动查询入口。
     */
    @Test
    void enabledProjectBranchUploadPersistsCandidateAndScopedPendingJobWithoutActivation() {
        CodeSnapshotJobView accepted = service(project(ProjectStatus.ENABLED, branchId)).upload(command(" ABCDEF1 "));

        assertThat(accepted.snapshotId()).isNotNull();
        assertThat(accepted.commit()).isEqualTo("abcdef1");
        assertThat(jdbc.queryForObject("select status from code_snapshot where id=?", String.class,
                accepted.snapshotId())).isEqualTo("CANDIDATE");
        assertThat(jdbc.queryForObject("select count(*) from code_snapshot where status='ACTIVE'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForMap("select project_id, branch_id, snapshot_id from background_job where id=?",
                accepted.jobId())).containsEntry("project_id", projectId)
                .containsEntry("branch_id", branchId)
                .containsEntry("snapshot_id", accepted.snapshotId());
    }

    /**
     * 业务目的：停用项目、跨项目分支和非法 commit 必须在最终登记前失败，并补偿任何已写对象而不留下候选或任务。
     */
    @Test
    void disabledProjectMismatchedBranchAndInvalidCommitLeaveNoBusinessRecords() {
        assertThatThrownBy(() -> service(project(ProjectStatus.DISABLED, branchId)).upload(command("abcdef1")))
                .isInstanceOf(ProjectDisabledException.class);
        assertThatThrownBy(() -> service(project(ProjectStatus.ENABLED, 8000000000000000156L)).upload(command("abcdef1")))
                .isInstanceOf(BranchNotFoundException.class);
        assertThatThrownBy(() -> service(project(ProjectStatus.ENABLED, branchId)).upload(command("bad")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("select count(*) from code_snapshot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from background_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stored_object", Integer.class)).isZero();
    }

    /**
     * 业务目的：同分支活动任务冲突必须回滚第二个候选并删除第二个对象，不能替换第一个输入或由完成顺序决定活动版本。
     */
    @Test
    void activeBranchJobConflictCompensatesSecondUpload() throws Exception {
        CodeSnapshotUploadService service = service(project(ProjectStatus.ENABLED, branchId));
        service.upload(command("abcdef1"));
        assertThat(jobs.findActiveByType(CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD)).isPresent();

        assertThatThrownBy(() -> service.upload(command("abcdef2")))
                .isInstanceOfSatisfying(io.github.loredock.platform.web.ApplicationException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo(io.github.loredock.platform.web.ErrorCode.CODE_SNAPSHOT_JOB_ACTIVE));
        assertThat(jdbc.queryForObject("select count(*) from code_snapshot", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from stored_object", Integer.class)).isEqualTo(1);
    }

    /**
     * 业务目的：对象成功后快照/任务事务失败必须整体回滚并幂等补偿；补偿自身失败也只能留下无快照引用、不可查询的孤儿对象。
     */
    @Test
    void registrationRollbackCompensatesObjectAndCompensationFailureLeavesOnlyUnreferencedObject() {
        jdbc.execute("""
                create function reject_code_job() returns trigger language plpgsql as $$
                begin raise exception 'simulated registration failure'; end $$
                """);
        jdbc.execute("""
                create trigger reject_code_job before insert on background_job
                for each row when (new.job_type = 'CODE_SNAPSHOT_BUILD') execute function reject_code_job()
                """);
        try {
            CodeSnapshotUploadService service = service(project(ProjectStatus.ENABLED, branchId));
            assertThatThrownBy(() -> service.upload(command("abcdef1"))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from code_snapshot", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from stored_object", Integer.class)).isZero();

            storage.failDelete = true;
            assertThatThrownBy(() -> service.upload(command("abcdef2"))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from code_snapshot", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from stored_object o
                    where not exists(select 1 from code_snapshot s where s.input_object_key=o.object_key)
                    """, Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger reject_code_job on background_job");
            jdbc.execute("drop function reject_code_job()");
        }
    }

    private CodeSnapshotUploadService service(ProjectService projects) {
        var repository = new CodeSnapshotDataService(snapshotMapper);
        var registration = new CodeSnapshotRegistrationService(
                projects, repository, jobs, new AuditMetadataFactory(Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> "ADMIN"),
                new DataSourceTransactionManager(dataSource));
        return new CodeSnapshotUploadService(
                new CodeSnapshotUploadValidator(properties()), storage,
                new ObjectStorageCodeSnapshotCompensation(storage), registration, repository);
    }

    private PersistentBackgroundJobService jobs(CountDownLatch releaseLatch) {
        JobHandler handler = new JobHandler() {
            @Override public String type() { return CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD; }
            @Override public void execute(io.github.loredock.job.service.JobExecutionContext context) {
                try {
                    releaseLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        return new PersistentBackgroundJobService(
                jobMapper, List.of(handler),
                new JobProperties(1, 1, 4, Duration.ofSeconds(1), Duration.ofMinutes(5)),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> "ADMIN", new AuditMetadataFactory(Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> "ADMIN"),
                new JobFailureClassifier(new SensitiveDataRedactor()));
    }

    private UploadCodeSnapshotCommand command(String commit) {
        byte[] zipHeader = new byte[]{'P', 'K', 3, 4, 0, 0};
        return new UploadCodeSnapshotCommand(projectId, branchId, commit,
                new CodeSnapshotUpload(new ByteArrayInputStream(zipHeader), "source.zip", "application/zip",
                        zipHeader.length));
    }

    private ProjectService project(ProjectStatus status, Long visibleBranchId) {
        ProjectService projects = mock(ProjectService.class);
        if (visibleBranchId.equals(branchId)) {
            when(projects.resolveScope(projectId, branchId)).thenReturn(new ProjectScope(
                    projectId, "network-tool", "Network", status == ProjectStatus.ENABLED,
                    visibleBranchId, "main"));
        } else {
            when(projects.resolveScope(projectId, branchId)).thenThrow(new BranchNotFoundException());
        }
        return projects;
    }

    private void seedProject(ProjectStatus status) {
        jdbc.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'network-tool', 'Network', '', 'Java', ?, ?, ?, 'test', 'test')
                """, projectId, status.name(), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', ?, ?, 'test', 'test')
                """, branchId, projectId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private CodeSnapshotProperties properties() {
        return new CodeSnapshotProperties(DataSize.ofMegabytes(1), 100, DataSize.ofMegabytes(1),
                DataSize.ofKilobytes(10), DataSize.ofMegabytes(2), BigDecimal.valueOf(100), 1000,
                Path.of("build/work/code"), Path.of("build/indexes/code"));
    }

    private static final class TestObjectStorage implements ObjectStorage {
        private final JdbcTemplate jdbc;
        private boolean failDelete;
        private final java.util.concurrent.atomic.AtomicLong keySequence =
                new java.util.concurrent.atomic.AtomicLong(8000000000000000157L);

        private TestObjectStorage(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public StoredObject put(InputStream input, ObjectMetadata metadata) {
            try { input.readAllBytes(); } catch (Exception exception) { throw new IllegalStateException(exception); }
            // 每次上传生成唯一对象键，避免同测试内两次上传共用固定键触发唯一约束。
            String key = Long.toString(keySequence.getAndIncrement());
            jdbc.update("""
                    insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                        sha256, created_at, updated_at, created_by, updated_by)
                    values (?, 'AVAILABLE', ?, ?, 6, ?, ?, ?, 'test', 'test')
                    """, key, metadata.originalFilename(), metadata.contentType(), "a".repeat(64),
                    Timestamp.from(NOW), Timestamp.from(NOW));
            return new StoredObject(key, metadata.originalFilename(), metadata.contentType(), 6, "a".repeat(64), NOW);
        }
        @Override public InputStream get(String objectKey) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(String objectKey) { return true; }
        @Override public void delete(String objectKey) {
            if (failDelete) throw new IllegalStateException("simulated cleanup failure");
            jdbc.update("delete from stored_object where object_key=?", objectKey);
        }
    }
}
