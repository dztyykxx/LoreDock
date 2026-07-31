package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.code.exception.CodeSnapshotNotActiveException;
import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.exception.CodeSnapshotObjectNotFoundException;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.code.service.archive.CodeSnapshotUploadValidator;
import io.github.loredock.code.service.storage.ObjectStorageCodeSnapshotCompensation;
import io.github.loredock.job.model.enums.JobStatus;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.storage.service.ObjectStorage;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodeSnapshotReindexServiceTest {

    private static final Long SNAPSHOT_ID = 8000000000000000076L;
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    /**
     * 业务目的：只有原始对象仍存在的当前活动快照才能提交重建，并返回同一快照/commit 的新任务受理状态。
     */
    @Test
    void activeSnapshotWithAvailableObjectCanSubmitReindex() {
        CodeSnapshotDataService snapshots = mock(CodeSnapshotDataService.class);
        ObjectStorage objects = mock(ObjectStorage.class);
        CodeSnapshotRegistrationService registration = mock(CodeSnapshotRegistrationService.class);
        when(snapshots.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot(CodeSnapshotStatus.ACTIVE)));
        when(objects.exists("opaque-object")).thenReturn(true);
        when(registration.registerReindex(SNAPSHOT_ID)).thenReturn(jobView());

        CodeSnapshotJobView result = service(snapshots, objects, registration).reindex(SNAPSHOT_ID);

        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.commit()).isEqualTo("abcdef1");
        assertThat(result.status()).isEqualTo(JobStatus.PENDING);
    }

    /**
     * 业务目的：未知和历史快照必须分别返回不存在与非活动冲突，且不能登记任何重建任务。
     */
    @Test
    void missingOrHistoricalSnapshotCannotBeReindexed() {
        CodeSnapshotDataService snapshots = mock(CodeSnapshotDataService.class);
        ObjectStorage objects = mock(ObjectStorage.class);
        CodeSnapshotRegistrationService registration = mock(CodeSnapshotRegistrationService.class);
        when(snapshots.findById(SNAPSHOT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(snapshots, objects, registration).reindex(SNAPSHOT_ID))
                .isInstanceOf(CodeSnapshotNotFoundException.class);

        when(snapshots.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot(CodeSnapshotStatus.RETIRED)));
        assertThatThrownBy(() -> service(snapshots, objects, registration).reindex(SNAPSHOT_ID))
                .isInstanceOf(CodeSnapshotNotActiveException.class);
        verify(registration, never()).registerReindex(SNAPSHOT_ID);
    }

    /**
     * 业务目的：活动快照的原始对象缺失时必须明确失败，不能借用历史对象或服务器任意目录重建。
     */
    @Test
    void missingOriginalObjectPreventsReindexWithoutFallback() {
        CodeSnapshotDataService snapshots = mock(CodeSnapshotDataService.class);
        ObjectStorage objects = mock(ObjectStorage.class);
        CodeSnapshotRegistrationService registration = mock(CodeSnapshotRegistrationService.class);
        when(snapshots.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot(CodeSnapshotStatus.ACTIVE)));
        when(objects.exists("opaque-object")).thenReturn(false);

        assertThatThrownBy(() -> service(snapshots, objects, registration).reindex(SNAPSHOT_ID))
                .isInstanceOf(CodeSnapshotObjectNotFoundException.class);
        verify(registration, never()).registerReindex(SNAPSHOT_ID);
    }

    private CodeSnapshotUploadService service(
            CodeSnapshotDataService snapshots,
            ObjectStorage objects,
            CodeSnapshotRegistrationService registration
    ) {
        return new CodeSnapshotUploadService(mock(CodeSnapshotUploadValidator.class), objects,
                mock(ObjectStorageCodeSnapshotCompensation.class), registration, snapshots);
    }

    private CodeSnapshotRecord snapshot(CodeSnapshotStatus status) {
        return new CodeSnapshotRecord(
                SNAPSHOT_ID, 8000000000000000077L, 8000000000000000078L, "abcdef1", "opaque-object", status,
                null, 3, 1, status == CodeSnapshotStatus.ACTIVE ? NOW : null,
                new AuditMetadata(NOW, NOW, "admin", "admin"));
    }

    private CodeSnapshotJobView jobView() {
        CodeSnapshotRecord snapshot = snapshot(CodeSnapshotStatus.ACTIVE);
        return new CodeSnapshotJobView(
                SNAPSHOT_ID, 8000000000000000079L, snapshot.projectId(), snapshot.branchId(), "abcdef1",
                JobStatus.PENDING, 0, 3, 1, NOW, null, null, null);
    }
}
