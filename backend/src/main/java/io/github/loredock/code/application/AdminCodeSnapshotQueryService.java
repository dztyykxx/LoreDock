package io.github.loredock.code.application;

import io.github.loredock.job.application.BackgroundJobService;
import io.github.loredock.job.domain.JobSnapshot;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * 管理代码快照与任务查询实现；任务查询会先按类型收口，再组装不含对象键的视图。
 */
@Service
public class AdminCodeSnapshotQueryService implements AdminCodeSnapshotQueryUseCase {

    private static final Set<String> CODE_JOB_TYPES = Set.of(
            CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD, CodeSnapshotJobTypes.CODE_SNAPSHOT_REINDEX);

    private final CodeSnapshotRepository snapshots;
    private final BackgroundJobService jobs;

    /**
     * @param snapshots 快照持久化端口
     * @param jobs 后台任务查询端口
     */
    public AdminCodeSnapshotQueryService(CodeSnapshotRepository snapshots, BackgroundJobService jobs) {
        this.snapshots = snapshots;
        this.jobs = jobs;
    }

    @Override
    public CodeSnapshotAdminPage list(AdminCodeSnapshotQuery query) {
        validate(query);
        return snapshots.listAdmin(query);
    }

    @Override
    public CodeSnapshotJobView getJob(UUID jobId) {
        JobSnapshot job = jobs.find(jobId)
                .filter(candidate -> CODE_JOB_TYPES.contains(candidate.type()))
                .filter(candidate -> candidate.snapshotId() != null)
                .orElseThrow(CodeSnapshotJobNotFoundException::new);
        CodeSnapshotRecord snapshot = snapshots.findById(job.snapshotId())
                .orElseThrow(CodeSnapshotJobNotFoundException::new);
        return new CodeSnapshotJobView(
                snapshot.id(), job.id(), snapshot.projectId(), snapshot.branchId(), snapshot.commit(),
                job.status(), job.progress(), snapshot.indexedFileCount(), snapshot.ignoredFileCount(),
                snapshot.audit().createdAt(), job.finishedAt(), job.errorCode(), job.errorMessage());
    }

    private void validate(AdminCodeSnapshotQuery query) {
        if (query == null || query.page() < 0 || query.size() < 1
                || query.size() > 100 || query.branchId() != null && query.projectId() == null) {
            throw new IllegalArgumentException("invalid code snapshot admin query");
        }
    }
}
