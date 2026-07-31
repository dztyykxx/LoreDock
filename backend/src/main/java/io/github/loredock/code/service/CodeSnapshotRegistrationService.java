package io.github.loredock.code.service;

import io.github.loredock.code.config.CodeSnapshotJobTypes;
import io.github.loredock.code.exception.CodeSnapshotNotActiveException;
import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.exception.ProjectDisabledException;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.job.model.request.JobRequest;
import io.github.loredock.job.model.snapshot.JobSnapshot;
import io.github.loredock.job.service.PersistentBackgroundJobService;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在一个 PostgreSQL 事务中校验管理范围、插入 CANDIDATE 并登记 PENDING 任务。
 * 后台任务服务通过 afterCommit 调度，事务回滚时处理器不会观察到半成品。
 */
@Service
public class CodeSnapshotRegistrationService {

    private final ProjectService projects;
    private final CodeSnapshotDataService snapshots;
    private final PersistentBackgroundJobService jobs;
    private final AuditMetadataFactory auditFactory;
    private final TransactionTemplate transaction;

    /** 创建上传登记事务服务。 */
    public CodeSnapshotRegistrationService(
            ProjectService projects,
            CodeSnapshotDataService snapshots,
            PersistentBackgroundJobService jobs,
            AuditMetadataFactory auditFactory,
            PlatformTransactionManager transactionManager
    ) {
        this.projects = projects;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.auditFactory = auditFactory;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param projectId 目标项目
     * @param branchId 必须属于目标项目的分支
     * @param commit 已规范化声明 commit
     * @param objectKey 已成功发布的原始 ZIP 对象键
     * @return 仅表示受理的任务状态
     */
    public CodeSnapshotJobView register(Long projectId, Long branchId, String commit, String objectKey) {
        CodeSnapshotJobView result = transaction.execute(status -> registerInTransaction(
                projectId, branchId, commit, objectKey));
        return Objects.requireNonNull(result, "代码快照登记事务未返回结果");
    }

    /**
     * 再次验证目标仍为活动快照，并在同一事务登记分支排他的重建任务。
     *
     * @param snapshotId 当前活动快照 Long
     * @return 非幂等新建任务的受理状态
     */
    public CodeSnapshotJobView registerReindex(Long snapshotId) {
        CodeSnapshotJobView result = transaction.execute(status -> registerReindexInTransaction(snapshotId));
        return Objects.requireNonNull(result, "代码快照重建登记事务未返回结果");
    }

    private CodeSnapshotJobView registerInTransaction(
            Long projectId, Long branchId, String commit, String objectKey
    ) {
        ProjectScope project = projects.resolveScope(projectId, branchId);
        if (!project.enabled()) {
            throw new ProjectDisabledException();
        }
        AuditMetadata audit = auditFactory.created();
        Long snapshotId = snapshots.insertCandidate(new CodeSnapshotRecord(
                null, projectId, branchId, commit, objectKey, CodeSnapshotStatus.CANDIDATE,
                null, 0, 0, null, audit));
        Long jobId = jobs.submitExclusiveByBranch(new JobRequest(
                CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD, objectKey, projectId, branchId, snapshotId));
        JobSnapshot job = jobs.find(jobId).orElseThrow();
        return new CodeSnapshotJobView(
                snapshotId, jobId, projectId, branchId, commit, job.status(), job.progress(),
                0, 0, audit.createdAt(), job.finishedAt(), null, null);
    }

    private CodeSnapshotJobView registerReindexInTransaction(Long snapshotId) {
        CodeSnapshotRecord snapshot = snapshots.findById(snapshotId)
                .orElseThrow(CodeSnapshotNotFoundException::new);
        if (snapshot.status() != CodeSnapshotStatus.ACTIVE) {
            throw new CodeSnapshotNotActiveException();
        }
        ProjectScope project = projects.resolveScope(snapshot.projectId(), snapshot.branchId());
        if (!project.enabled()) {
            throw new ProjectDisabledException();
        }
        Long jobId = jobs.submitExclusiveByBranch(new JobRequest(
                CodeSnapshotJobTypes.CODE_SNAPSHOT_REINDEX, snapshot.inputObjectKey(), snapshot.projectId(),
                snapshot.branchId(), snapshot.id()));
        JobSnapshot job = jobs.find(jobId).orElseThrow();
        return new CodeSnapshotJobView(
                snapshot.id(), jobId, snapshot.projectId(), snapshot.branchId(), snapshot.commit(),
                job.status(), job.progress(), snapshot.indexedFileCount(), snapshot.ignoredFileCount(),
                snapshot.audit().createdAt(), job.finishedAt(), null, null);
    }
}
