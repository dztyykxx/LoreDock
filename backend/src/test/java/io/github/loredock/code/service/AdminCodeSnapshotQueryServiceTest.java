package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.code.config.CodeSnapshotJobTypes;
import io.github.loredock.code.exception.CodeSnapshotJobNotFoundException;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.job.model.snapshot.JobSnapshot;
import io.github.loredock.job.service.PersistentBackgroundJobService;
import io.github.loredock.platform.persistence.AuditMetadata;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminCodeSnapshotQueryServiceTest {

    private static final Long SNAPSHOT_ID = 2891640495451214098L;
    private static final Long JOB_ID = 5783280990902428195L;
    private static final Long PROJECT_ID = 1674921486353642292L;
    private static final Long BRANCH_ID = 4566561981804856389L;
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    /**
     * 业务目的：普通后台任务 ID 不能从代码任务入口被枚举，必须与未知 ID 统一返回 404。
     */
    @Test
    void nonCodeJobIsHiddenAsNotFound() {
        CodeSnapshotDataService snapshots = mock(CodeSnapshotDataService.class);
        PersistentBackgroundJobService jobs = mock(PersistentBackgroundJobService.class);
        when(jobs.find(JOB_ID)).thenReturn(Optional.of(job("KNOWLEDGE_REINDEX")));

        AdminCodeSnapshotQueryService service = new AdminCodeSnapshotQueryService(snapshots, jobs);

        assertThatThrownBy(() -> service.getJob(JOB_ID))
                .isInstanceOf(CodeSnapshotJobNotFoundException.class);
    }

    /**
     * 业务目的：代码任务状态必须使用任务进度和失败语义，同时只从快照取业务范围与计数，不暴露对象键。
     */
    @Test
    void codeJobCombinesSanitizedTaskAndSnapshotMetadata() {
        CodeSnapshotDataService snapshots = mock(CodeSnapshotDataService.class);
        PersistentBackgroundJobService jobs = mock(PersistentBackgroundJobService.class);
        when(jobs.find(JOB_ID)).thenReturn(Optional.of(job(CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD)));
        when(snapshots.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot()));

        CodeSnapshotJobView view = new AdminCodeSnapshotQueryService(snapshots, jobs).getJob(JOB_ID);

        assertThat(view.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(view.projectId()).isEqualTo(PROJECT_ID);
        assertThat(view.commit()).isEqualTo("abcdef1");
        assertThat(view.progress()).isEqualTo(65);
        assertThat(view.indexedFileCount()).isEqualTo(7);
        assertThat(view.failureCode()).isEqualTo("CODE_SNAPSHOT_ARCHIVE_INVALID");
    }

    private JobSnapshot job(String type) {
        return new JobSnapshot(
                JOB_ID, type, JobStatus.FAILED, 65, "objects/private-key", PROJECT_ID, BRANCH_ID, SNAPSHOT_ID,
                NOW, NOW.plusSeconds(5), NOW.plusSeconds(4), "instance", "CODE_SNAPSHOT_ARCHIVE_INVALID",
                "归档结构不安全");
    }

    private CodeSnapshotRecord snapshot() {
        return new CodeSnapshotRecord(
                SNAPSHOT_ID, PROJECT_ID, BRANCH_ID, "abcdef1", "objects/private-key",
                CodeSnapshotStatus.FAILED, null, 7, 2, null,
                new AuditMetadata(NOW.minusSeconds(1), NOW, "admin", "admin"));
    }
}
