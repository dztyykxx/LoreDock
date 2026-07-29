package io.github.loredock.job.domain;

import io.github.loredock.job.application.JobFailureClassifier;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackgroundJobTest {

    private static final Instant CREATED = Instant.parse("2026-07-29T10:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-07-29T10:01:00Z");
    private static final Instant FINISHED = Instant.parse("2026-07-29T10:02:00Z");

    /**
     * 业务目的：成功工作只能从运行态终结并固定为 100% 进度，防止任务显示成功却仍保留错误或不完整进度。
     */
    @Test
    void runningJobSucceedsWithTerminalStateAndCompletionTime() {
        BackgroundJob job = runningJob();

        job.succeed(FINISHED);

        assertThat(job.snapshot().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.snapshot().progress()).isEqualTo(100);
        assertThat(job.snapshot().finishedAt()).isEqualTo(FINISHED);
        assertThat(job.snapshot().errorCode()).isNull();
    }

    /**
     * 业务目的：失败和取消都必须形成有完成时间的终态，防止运行中任务无限悬挂。
     */
    @Test
    void runningJobCanFailOrBeCancelled() {
        BackgroundJob failed = runningJob();
        BackgroundJob cancelled = runningJob();

        failed.fail(FINISHED, "IMPORT_FAILED", "safe diagnostic");
        cancelled.cancel(FINISHED);

        assertThat(failed.snapshot().status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.snapshot().errorCode()).isEqualTo("IMPORT_FAILED");
        assertThat(cancelled.snapshot().status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.snapshot().finishedAt()).isEqualTo(FINISHED);
    }

    /**
     * 业务目的：终态任务不得重新运行或再次终结，防止并发完成覆盖真实结果。
     */
    @Test
    void terminalJobRejectsFurtherStateTransitions() {
        BackgroundJob job = runningJob();
        job.succeed(FINISHED);

        assertThatThrownBy(() -> job.start(FINISHED, "instance-2"))
                .isInstanceOf(InvalidJobTransitionException.class)
                .hasMessageContaining("SUCCEEDED");
        assertThatThrownBy(() -> job.fail(FINISHED, "LATE_FAILURE", "late"))
                .isInstanceOf(InvalidJobTransitionException.class);
    }

    /**
     * 业务目的：任务进度必须在合法范围内单调增加，防止 UI 和恢复逻辑观察到倒退或超过 100 的状态。
     */
    @Test
    void runningProgressRejectsRegressionAndOutOfRange() {
        BackgroundJob job = runningJob();
        job.updateProgress(40, FINISHED);

        assertThatThrownBy(() -> job.updateProgress(39, FINISHED))
                .isInstanceOf(InvalidJobTransitionException.class);
        assertThatThrownBy(() -> job.updateProgress(100, FINISHED))
                .isInstanceOf(InvalidJobTransitionException.class);
        assertThat(job.snapshot().progress()).isEqualTo(40);
    }

    /**
     * 业务目的：原始任务异常中的密码、Token、连接串和路径不得进入持久错误摘要，防止任务表成为敏感信息存储。
     */
    @Test
    void failureClassifierStoresStableCodeAndRedactedSummary() {
        JobFailureClassifier classifier = new JobFailureClassifier(new SensitiveDataRedactor());

        JobFailure failure = classifier.classify(new IllegalStateException(
                "password=secret token=abc jdbc:postgresql://internal/db /Users/demo/private"));

        assertThat(failure.code()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(failure.message())
                .doesNotContain("secret", "abc", "jdbc:postgresql", "/Users/demo")
                .contains("[REDACTED]");
    }

    private BackgroundJob runningJob() {
        BackgroundJob job = BackgroundJob.pending(UUID.randomUUID(), "TEST", null, CREATED);
        job.start(STARTED, "instance-1");
        return job;
    }
}
