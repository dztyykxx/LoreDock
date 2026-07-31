package io.github.loredock.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.job.config.JobProperties;
import io.github.loredock.job.mapper.BackgroundJobMapper;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.job.model.request.JobRequest;
import io.github.loredock.persistence.MybatisMapperFactory;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import io.github.loredock.support.TestIds;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class BranchScopedBackgroundJobServiceIT {

    private static final Instant NOW = Instant.parse("2026-07-30T05:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_branch_job_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static BackgroundJobMapper mapper;
    private final java.util.ArrayList<PersistentBackgroundJobService> services = new java.util.ArrayList<>();

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        mapper = MybatisMapperFactory.create(dataSource, BackgroundJobMapper.class);
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("delete from code_index_generation");
        jdbc.update("delete from background_job");
        jdbc.update("delete from code_snapshot");
        jdbc.update("delete from stored_object");
        jdbc.update("delete from project_branch");
        jdbc.update("delete from project_space");
    }

    @AfterEach
    void closeServices() {
        services.forEach(PersistentBackgroundJobService::close);
    }

    /**
     * 业务目的：构建与重建必须共享同分支数据库排他约束并持久化完整范围，防止两个任务以完成顺序争夺活动快照。
     */
    @Test
    void buildAndReindexShareBranchExclusionWhileOtherBranchRemainsIndependent() throws Exception {
        Scope first = seedScope("first", "main");
        Scope second = seedBranch(first.projectId(), "feature/a", "second-object");
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        PersistentBackgroundJobService service = service(
                blockingHandler("CODE_SNAPSHOT_BUILD", started, release),
                blockingHandler("CODE_SNAPSHOT_REINDEX", started, release));

        Long firstJob = service.submitExclusiveByBranch(request("CODE_SNAPSHOT_BUILD", first));
        assertThatThrownBy(() -> service.submitExclusiveByBranch(request("CODE_SNAPSHOT_REINDEX", first)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CODE_SNAPSHOT_JOB_ACTIVE));
        Long otherBranchJob = service.submitExclusiveByBranch(request("CODE_SNAPSHOT_BUILD", second));

        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        var firstSnapshot = service.find(firstJob).orElseThrow();
        assertThat(firstSnapshot.projectId()).isEqualTo(first.projectId());
        assertThat(firstSnapshot.branchId()).isEqualTo(first.branchId());
        assertThat(firstSnapshot.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(service.find(otherBranchJob)).isPresent();
        release.countDown();
    }

    /**
     * 业务目的：调用方事务回滚时任务记录和执行副作用都必须消失，防止处理器读取尚未提交或永远不存在的候选快照。
     */
    @Test
    void outerTransactionRollbackNeitherPersistsNorSchedulesJob() {
        Scope scope = seedScope("rollback", "main");
        CountDownLatch executed = new CountDownLatch(1);
        PersistentBackgroundJobService service = service(handler("CODE_SNAPSHOT_BUILD", context -> executed.countDown()));
        AtomicReference<Long> jobId = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            jobId.set(service.submitExclusiveByBranch(request("CODE_SNAPSHOT_BUILD", scope)));
            status.setRollbackOnly();
        });

        assertThat(service.find(jobId.get())).isEmpty();
        assertThat(executed.getCount()).isEqualTo(1);
    }

    /**
     * 业务目的：存在调用方事务时处理器只能在提交完成后启动，防止后台线程抢先读取未提交的快照和任务范围。
     */
    @Test
    void handlerStartsOnlyAfterOuterTransactionCommits() {
        Scope scope = seedScope("commit", "main");
        CountDownLatch executed = new CountDownLatch(1);
        PersistentBackgroundJobService service = service(handler("CODE_SNAPSHOT_BUILD", context -> executed.countDown()));
        AtomicReference<Long> jobId = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            jobId.set(service.submitExclusiveByBranch(request("CODE_SNAPSHOT_BUILD", scope)));
            assertThat(executed.getCount()).isEqualTo(1);
        });

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(executed.getCount()).isZero();
            assertThat(service.find(jobId.get()).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        });
    }

    private PersistentBackgroundJobService service(JobHandler... handlers) {
        PersistentBackgroundJobService service = new PersistentBackgroundJobService(
                mapper, List.of(handlers),
                new JobProperties(2, 2, 4, Duration.ofSeconds(2), Duration.ofMinutes(5)),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> "SYSTEM", new AuditMetadataFactory(Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> "SYSTEM"),
                new JobFailureClassifier(new SensitiveDataRedactor()));
        services.add(service);
        return service;
    }

    private JobRequest request(String type, Scope scope) {
        return new JobRequest(type, scope.objectKey(), scope.projectId(), scope.branchId(), scope.snapshotId());
    }

    private JobHandler blockingHandler(String type, CountDownLatch started, CountDownLatch release) {
        return handler(type, context -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
        });
    }

    private JobHandler handler(String type, java.util.function.Consumer<JobExecutionContext> work) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void execute(JobExecutionContext context) {
                work.accept(context);
            }
        };
    }

    private Scope seedScope(String prefix, String branchName) {
        Long projectId = TestIds.next();
        jdbc.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '', '', 'ENABLED', ?, ?, 'test', 'test')
                """, projectId, prefix + "-project", prefix, Timestamp.from(NOW), Timestamp.from(NOW));
        return seedBranch(projectId, branchName, prefix + "-object");
    }

    private Scope seedBranch(Long projectId, String branchName, String objectKey) {
        // 分支与快照使用进程内唯一 ID，避免同测试内多次 seed 共用固定主键。
        Long branchId = TestIds.next();
        Long snapshotId = TestIds.next();
        jdbc.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'test', 'test')
                """, branchId, projectId, branchName, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values (?, 'AVAILABLE', 'code.zip', 'application/zip', 4, ?, ?, ?, 'test', 'test')
                """, objectKey, "a".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into code_snapshot(id, project_id, branch_id, commit_hash, input_object_key, status,
                    indexed_file_count, ignored_file_count, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, 'abcdef1', ?, 'CANDIDATE', 0, 0, ?, ?, 'test', 'test')
                """, snapshotId, projectId, branchId, objectKey, Timestamp.from(NOW), Timestamp.from(NOW));
        return new Scope(projectId, branchId, snapshotId, objectKey);
    }

    private record Scope(Long projectId, Long branchId, Long snapshotId, String objectKey) {
    }
}
