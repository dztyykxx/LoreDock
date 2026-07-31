package io.github.loredock.code.service;

import io.github.loredock.code.exception.CodeSnapshotNotActiveException;
import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.exception.CodeSnapshotObjectNotFoundException;
import io.github.loredock.code.model.CodeCommit;
import io.github.loredock.code.model.command.UploadCodeSnapshotCommand;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.code.model.result.ValidatedCodeSnapshotUpload;
import io.github.loredock.code.service.archive.CodeSnapshotUploadValidator;
import io.github.loredock.code.service.storage.ObjectStorageCodeSnapshotCompensation;
import io.github.loredock.storage.api.ObjectMetadata;
import io.github.loredock.storage.api.ObjectStorage;
import io.github.loredock.storage.api.StoredObject;
import org.springframework.stereotype.Service;

/**
 * 代码快照上传协调器：先校验并流式持久化对象，再调用短事务登记候选与任务。
 * 跨对象存储与 PostgreSQL 无法使用 XA，登记失败时显式幂等补偿未引用对象。
 */
@Service
public class CodeSnapshotUploadService {

    private final CodeSnapshotUploadValidator uploads;
    private final ObjectStorage objectStorage;
    private final ObjectStorageCodeSnapshotCompensation compensation;
    private final CodeSnapshotRegistrationService registration;
    private final CodeSnapshotDataService snapshots;

    /** 创建上传协调器。 */
    public CodeSnapshotUploadService(
            CodeSnapshotUploadValidator uploads,
            ObjectStorage objectStorage,
            ObjectStorageCodeSnapshotCompensation compensation,
            CodeSnapshotRegistrationService registration,
            CodeSnapshotDataService snapshots
    ) {
        this.uploads = uploads;
        this.objectStorage = objectStorage;
        this.compensation = compensation;
        this.registration = registration;
        this.snapshots = snapshots;
    }

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

    public CodeSnapshotJobView reindex(Long snapshotId) {
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
