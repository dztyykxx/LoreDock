package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeCommit;
import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.storage.application.ObjectStorage;
import io.github.loredock.storage.domain.ObjectMetadata;
import io.github.loredock.storage.domain.StoredObject;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 代码快照上传协调器：先校验并流式持久化对象，再调用短事务登记候选与任务。
 * 跨对象存储与 PostgreSQL 无法使用 XA，登记失败时显式幂等补偿未引用对象。
 */
@Service
public class CodeSnapshotUploadService implements CodeSnapshotCommandUseCase {

    private final CodeSnapshotUploadValidationPort uploads;
    private final ObjectStorage objectStorage;
    private final CodeSnapshotObjectCompensation compensation;
    private final CodeSnapshotRegistrationService registration;
    private final CodeSnapshotRepository snapshots;

    /** 创建上传协调器。 */
    public CodeSnapshotUploadService(
            CodeSnapshotUploadValidationPort uploads,
            ObjectStorage objectStorage,
            CodeSnapshotObjectCompensation compensation,
            CodeSnapshotRegistrationService registration,
            CodeSnapshotRepository snapshots
    ) {
        this.uploads = uploads;
        this.objectStorage = objectStorage;
        this.compensation = compensation;
        this.registration = registration;
        this.snapshots = snapshots;
    }

    @Override
    public CodeSnapshotJobView upload(UploadCodeSnapshotCommand command) {
        CodeCommit commit = new CodeCommit(command.commit());
        ValidatedCodeSnapshotUpload validated = uploads.validate(command.upload());
        StoredObject object = objectStorage.put(
                validated.input(), new ObjectMetadata(command.upload().originalFilename(), validated.contentType()));
        try {
            return registration.register(command.projectId(), command.branchId(), commit.value(), object.objectKey());
        } catch (RuntimeException failure) {
            compensation.deleteUnreferenced(object.objectKey());
            throw failure;
        }
    }

    @Override
    public CodeSnapshotJobView reindex(UUID snapshotId) {
        CodeSnapshotRecord snapshot = snapshots.findById(snapshotId)
                .orElseThrow(CodeSnapshotNotFoundException::new);
        if (snapshot.status() != CodeSnapshotStatus.ACTIVE) {
            throw new CodeSnapshotNotActiveException();
        }
        if (!objectStorage.exists(snapshot.inputObjectKey())) {
            throw new CodeSnapshotObjectNotFoundException();
        }
        return registration.registerReindex(snapshotId);
    }
}
