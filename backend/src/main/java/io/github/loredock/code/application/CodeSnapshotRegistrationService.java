package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.job.application.BackgroundJobService;
import io.github.loredock.job.application.JobRequest;
import io.github.loredock.job.domain.JobSnapshot;
import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.platform.audit.AuditMetadataFactory;
import io.github.loredock.project.application.AdminProjectDetailView;
import io.github.loredock.project.application.AdminProjectQueryUseCase;
import io.github.loredock.project.application.BranchNotFoundException;
import io.github.loredock.project.domain.ProjectStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * 在一个 PostgreSQL 事务中校验管理范围、插入 CANDIDATE 并登记 PENDING 任务。
 * 后台任务服务通过 afterCommit 调度，事务回滚时处理器不会观察到半成品。
 */
@Service
public class CodeSnapshotRegistrationService {

    private final AdminProjectQueryUseCase projects;
    private final CodeSnapshotRepository snapshots;
    private final BackgroundJobService jobs;
    private final AuditMetadataFactory auditFactory;
    private final TransactionTemplate transaction;

    /** 创建上传登记事务服务。 */
    public CodeSnapshotRegistrationService(
            AdminProjectQueryUseCase projects,
            CodeSnapshotRepository snapshots,
            BackgroundJobService jobs,
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
    public CodeSnapshotJobView register(UUID projectId, UUID branchId, String commit, String objectKey) {
        CodeSnapshotJobView result = transaction.execute(status -> registerInTransaction(
                projectId, branchId, commit, objectKey));
        return Objects.requireNonNull(result, "代码快照登记事务未返回结果");
    }

    /**
     * 再次验证目标仍为活动快照，并在同一事务登记分支排他的重建任务。
     *
     * @param snapshotId 当前活动快照 UUID
     * @return 非幂等新建任务的受理状态
     */
    public CodeSnapshotJobView registerReindex(UUID snapshotId) {
        CodeSnapshotJobView result = transaction.execute(status -> registerReindexInTransaction(snapshotId));
        return Objects.requireNonNull(result, "代码快照重建登记事务未返回结果");
    }

    private CodeSnapshotJobView registerInTransaction(
            UUID projectId, UUID branchId, String commit, String objectKey
    ) {
        AdminProjectDetailView project = projects.getProject(projectId);
        if (project.status() == ProjectStatus.DISABLED) {
            throw new ProjectDisabledException();
        }
        boolean ownedBranch = project.branches().stream().anyMatch(branch -> branch.id().equals(branchId));
        if (!ownedBranch) {
            throw new BranchNotFoundException();
        }
        AuditMetadata audit = auditFactory.created();
        UUID snapshotId = UUID.randomUUID();
        snapshots.insertCandidate(new CodeSnapshotRecord(
                snapshotId, projectId, branchId, commit, objectKey, CodeSnapshotStatus.CANDIDATE,
                null, 0, 0, null, audit));
        UUID jobId = jobs.submitExclusiveByBranch(new JobRequest(
                CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD, objectKey, projectId, branchId, snapshotId));
        JobSnapshot job = jobs.find(jobId).orElseThrow();
        return new CodeSnapshotJobView(
                snapshotId, jobId, projectId, branchId, commit, job.status(), job.progress(),
                0, 0, audit.createdAt(), job.finishedAt(), null, null);
    }

    private CodeSnapshotJobView registerReindexInTransaction(UUID snapshotId) {
        CodeSnapshotRecord snapshot = snapshots.findById(snapshotId)
                .orElseThrow(CodeSnapshotNotFoundException::new);
        if (snapshot.status() != CodeSnapshotStatus.ACTIVE) {
            throw new CodeSnapshotNotActiveException();
        }
        AdminProjectDetailView project = projects.getProject(snapshot.projectId());
        if (project.status() == ProjectStatus.DISABLED) {
            throw new ProjectDisabledException();
        }
        UUID jobId = jobs.submitExclusiveByBranch(new JobRequest(
                CodeSnapshotJobTypes.CODE_SNAPSHOT_REINDEX, snapshot.inputObjectKey(), snapshot.projectId(),
                snapshot.branchId(), snapshot.id()));
        JobSnapshot job = jobs.find(jobId).orElseThrow();
        return new CodeSnapshotJobView(
                snapshot.id(), jobId, snapshot.projectId(), snapshot.branchId(), snapshot.commit(),
                job.status(), job.progress(), snapshot.indexedFileCount(), snapshot.ignoredFileCount(),
                snapshot.audit().createdAt(), job.finishedAt(), null, null);
    }
}
