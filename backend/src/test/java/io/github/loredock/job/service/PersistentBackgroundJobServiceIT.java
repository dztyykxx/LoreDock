package io.github.loredock.job.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.job.config.JobProperties;
import io.github.loredock.job.mapper.BackgroundJobMapper;
import io.github.loredock.job.model.BackgroundJob;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.job.model.request.JobRequest;
import io.github.loredock.job.model.snapshot.JobSnapshot;
import io.github.loredock.job.scheduler.JobRecovery;
import io.github.loredock.persistence.MybatisMapperFactory;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PersistentBackgroundJobServiceIT {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_job_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    private static JdbcTemplate jdbcTemplate;
    private static BackgroundJobMapper backgroundJobMapper;
    private final List<PersistentBackgroundJobService> services = new ArrayList<>();

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        backgroundJobMapper = MybatisMapperFactory.create(dataSource, BackgroundJobMapper.class);
    }

    @BeforeEach
    void clearJobs() {
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from stored_object");
    }

    @AfterEach
    void closeServices() {
        services.forEach(PersistentBackgroundJobService::close);
    }

    /**
     * 业务目的：任务必须先有数据库记录再执行，且成功和失败互不影响，防止异常工作丢失记录或拖垮线程池。
     */
    @Test
    void jobPersistsBeforeExecutionAndFailuresStayIsolated() {
        PersistentBackgroundJobService service = service(
                properties(2, 4),
                handler("SUCCESS", context -> context.updateProgress(60)),
                handler("FAIL", context -> {
                    throw new IllegalStateException("token=secret-value");
                })
        );

        var failedId = service.submit(new JobRequest("FAIL", null));
        var succeededId = service.submit(new JobRequest("SUCCESS", null));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from background_job where id in (?, ?)", Integer.class, failedId, succeededId))
                .isEqualTo(2);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(service.find(failedId).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
            assertThat(service.find(succeededId).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        });
        JobSnapshot failure = service.find(failedId).orElseThrow();
        assertThat(failure.errorCode()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(failure.errorMessage()).doesNotContain("secret-value").contains("[REDACTED]");
        assertThat(service.find(succeededId).orElseThrow().progress()).isEqualTo(100);
    }

    /**
     * 业务目的：执行器满载时新任务仍必须保留可诊断失败记录，防止无界排队或提交结果凭空丢失。
     */
    @Test
    void fullExecutorMarksJobAsCapacityFailure() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PersistentBackgroundJobService service = service(
                properties(1, 0),
                handler("BLOCK", context -> {
                    started.countDown();
                    await(release);
                }),
                handler("SUCCESS", context -> { })
        );
        service.submit(new JobRequest("BLOCK", null));
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

        var rejectedId = service.submit(new JobRequest("SUCCESS", null));

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            JobSnapshot rejected = service.find(rejectedId).orElseThrow();
            assertThat(rejected.status()).isEqualTo(JobStatus.FAILED);
            assertThat(rejected.errorCode()).isEqualTo("CAPACITY_EXCEEDED");
        });
        release.countDown();
    }

    /**
     * 业务目的：进度和心跳要在工作尚未完成时可见，取消与随后到达的成功结果竞争时必须保持取消终态。
     */
    @Test
    void progressIsVisibleAndCancellationWinsLateSuccess() throws Exception {
        CountDownLatch progressWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PersistentBackgroundJobService service = service(
                properties(1, 4),
                handler("PROGRESS", context -> {
                    context.updateProgress(55);
                    context.heartbeat();
                    progressWritten.countDown();
                    await(release);
                })
        );
        var jobId = service.submit(new JobRequest("PROGRESS", null));
        assertThat(progressWritten.await(3, TimeUnit.SECONDS)).isTrue();

        JobSnapshot running = service.find(jobId).orElseThrow();
        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(running.progress()).isEqualTo(55);
        assertThat(running.heartbeatAt()).isNotNull();

        service.cancel(jobId);
        release.countDown();

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(service.find(jobId).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED));
    }

    /**
     * 业务目的：进程重启只能终结已失去心跳的运行任务，防止误伤仍有效任务或自动重放未知副作用。
     */
    @Test
    void recoveryFailsOnlyStaleRunningJobsWithoutReplay() {
        BackgroundJob stale = runningJob(NOW.minus(Duration.ofMinutes(6)), "old-instance");
        BackgroundJob fresh = runningJob(NOW.minus(Duration.ofMinutes(4)), "live-instance");
        insertJob(stale.snapshot());
        insertJob(fresh.snapshot());

        JobRecovery recovery = new JobRecovery(
                backgroundJobMapper,
                properties(1, 1),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                () -> "SYSTEM"
        );

        assertThat(recovery.recoverStale()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from background_job where id=?", String.class, stale.snapshot().id()))
                .isEqualTo(JobStatus.FAILED.name());
        assertThat(jdbcTemplate.queryForObject(
                "select error_code from background_job where id=?", String.class, stale.snapshot().id()))
                .isEqualTo("PROCESS_INTERRUPTED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from background_job where id=?", String.class, fresh.snapshot().id()))
                .isEqualTo(JobStatus.RUNNING.name());
    }

    /**
     * 业务目的：single-flight 只复用同类型活动任务，默认 submit 和其他类型仍独立，终态后可以重新提交。
     */
    @Test
    void singleFlightReusesOnlySameTypeActiveJobAndAllowsNewJobAfterTerminal() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean persistedBeforeExecution = new AtomicBoolean(true);
        JobHandler typeA = handler("TYPE_A", context -> {
            persistedBeforeExecution.compareAndSet(true, jdbcTemplate.queryForObject(
                    "select count(*) > 0 from background_job where id = ?",
                    Boolean.class, context.jobId()));
            started.countDown();
            await(release);
        });
        JobHandler typeB = handler("TYPE_B", context -> {
            started.countDown();
            await(release);
        });
        PersistentBackgroundJobService service = service(properties(4, 4), typeA, typeB);

        var shared = service.submitSingleFlight(new JobRequest("TYPE_A", null));
        var reused = service.submitSingleFlight(new JobRequest("TYPE_A", null));
        var otherType = service.submitSingleFlight(new JobRequest("TYPE_B", null));
        var defaultFirst = service.submit(new JobRequest("TYPE_A", null));
        var defaultSecond = service.submit(new JobRequest("TYPE_A", null));

        assertThat(reused).isEqualTo(shared);
        assertThat(otherType).isNotEqualTo(shared);
        assertThat(defaultFirst).isNotEqualTo(defaultSecond).isNotEqualTo(shared);
        // 固定测试时钟让三个 TYPE_A 的 createdAt 相同，此时仓储契约按 Long 字符序稳定选择。
        var stableFirst = java.util.stream.Stream.of(shared, defaultFirst, defaultSecond)
                .min(java.util.Comparator.naturalOrder())
                .orElseThrow();
        assertThat(service.findActiveByType("TYPE_A")).isPresent()
                .get().extracting(JobSnapshot::id).isEqualTo(stableFirst);
        assertThat(persistedBeforeExecution).isTrue();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(service.find(shared).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED));

        var afterTerminal = service.submitSingleFlight(new JobRequest("TYPE_A", null));
        assertThat(afterTerminal).isNotEqualTo(shared);
    }

    private PersistentBackgroundJobService service(JobProperties properties, JobHandler... handlers) {
        var auditFactory = new AuditMetadataFactory(Clock.fixed(NOW, java.time.ZoneOffset.UTC), () -> "SYSTEM");
        PersistentBackgroundJobService service = new PersistentBackgroundJobService(
                backgroundJobMapper,
                List.of(handlers),
                properties,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                () -> "SYSTEM",
                auditFactory,
                new JobFailureClassifier(new SensitiveDataRedactor())
        );
        services.add(service);
        return service;
    }

    private void insertJob(JobSnapshot job) {
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, input_object_key, project_id, branch_id,
                    snapshot_id, started_at, finished_at, heartbeat_at, owner_instance, error_code, error_message,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SYSTEM', 'SYSTEM')
                """, job.id(), job.type(), job.status().name(), job.progress(), job.inputObjectKey(), job.projectId(),
                job.branchId(), job.snapshotId(), timestamp(job.startedAt()), timestamp(job.finishedAt()),
                timestamp(job.heartbeatAt()), job.ownerInstance(), job.errorCode(), job.errorMessage(),
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private JobProperties properties(int threads, int queueCapacity) {
        return new JobProperties(
                threads, threads, queueCapacity,
                Duration.ofSeconds(2), Duration.ofMinutes(5)
        );
    }

    private BackgroundJob runningJob(Instant heartbeatAt, String instanceId) {
        BackgroundJob job = BackgroundJob.pending(
                8000000000000000004L, "RECOVERY_TEST", null, heartbeatAt
        );
        job.start(heartbeatAt, instanceId);
        return job;
    }

    private JobHandler handler(String type, Consumer<JobExecutionContext> work) {
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

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
