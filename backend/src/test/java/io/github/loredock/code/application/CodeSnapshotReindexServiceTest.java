package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.job.domain.JobStatus;
import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.storage.application.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSnapshotReindexServiceTest {

    private static final UUID SNAPSHOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    /**
     * 业务目的：只有原始对象仍存在的当前活动快照才能提交重建，并返回同一快照/commit 的新任务受理状态。
     */
    @Test
    void activeSnapshotWithAvailableObjectCanSubmitReindex() {
        CodeSnapshotRepository snapshots = mock(CodeSnapshotRepository.class);
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
        CodeSnapshotRepository snapshots = mock(CodeSnapshotRepository.class);
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
        CodeSnapshotRepository snapshots = mock(CodeSnapshotRepository.class);
        ObjectStorage objects = mock(ObjectStorage.class);
        CodeSnapshotRegistrationService registration = mock(CodeSnapshotRegistrationService.class);
        when(snapshots.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot(CodeSnapshotStatus.ACTIVE)));
        when(objects.exists("opaque-object")).thenReturn(false);

        assertThatThrownBy(() -> service(snapshots, objects, registration).reindex(SNAPSHOT_ID))
                .isInstanceOf(CodeSnapshotObjectNotFoundException.class);
        verify(registration, never()).registerReindex(SNAPSHOT_ID);
    }

    private CodeSnapshotUploadService service(
            CodeSnapshotRepository snapshots,
            ObjectStorage objects,
            CodeSnapshotRegistrationService registration
    ) {
        return new CodeSnapshotUploadService(mock(CodeSnapshotUploadValidationPort.class), objects,
                mock(CodeSnapshotObjectCompensation.class), registration, snapshots);
    }

    private CodeSnapshotRecord snapshot(CodeSnapshotStatus status) {
        return new CodeSnapshotRecord(
                SNAPSHOT_ID, UUID.randomUUID(), UUID.randomUUID(), "abcdef1", "opaque-object", status,
                null, 3, 1, status == CodeSnapshotStatus.ACTIVE ? NOW : null,
                new AuditMetadata(NOW, NOW, "admin", "admin"));
    }

    private CodeSnapshotJobView jobView() {
        CodeSnapshotRecord snapshot = snapshot(CodeSnapshotStatus.ACTIVE);
        return new CodeSnapshotJobView(
                SNAPSHOT_ID, UUID.randomUUID(), snapshot.projectId(), snapshot.branchId(), "abcdef1",
                JobStatus.PENDING, 0, 3, 1, NOW, null, null, null);
    }
}
