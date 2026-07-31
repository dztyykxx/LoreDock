package io.github.loredock.code.service;

import io.github.loredock.code.config.CodeSnapshotJobTypes;
import io.github.loredock.code.exception.CodeSnapshotNotActiveException;
import io.github.loredock.code.exception.CodeSnapshotNotFoundException;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotGenerationResult;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.code.model.result.CodeSnapshotReindexActivation;
import io.github.loredock.code.service.index.LuceneIndexHandleRegistry;
import io.github.loredock.job.service.JobExecutionContext;
import io.github.loredock.job.service.JobHandler;
import org.springframework.stereotype.Component;

/** 活动快照重建处理器；任何失败只终结新 generation，不改变当前活动查询入口。 */
@Component
public class CodeSnapshotReindexJobHandler implements JobHandler {

    private final CodeSnapshotDataService snapshots;
    private final CodeSnapshotLifecycleService lifecycle;
    private final CodeSnapshotGenerationBuilder builder;
    private final LuceneIndexHandleRegistry retirements;

    /** 创建复用同一安全索引流水线的重建处理器。 */
    public CodeSnapshotReindexJobHandler(
            CodeSnapshotDataService snapshots,
            CodeSnapshotLifecycleService lifecycle,
            CodeSnapshotGenerationBuilder builder,
            LuceneIndexHandleRegistry retirements
    ) {
        this.snapshots = snapshots;
        this.lifecycle = lifecycle;
        this.builder = builder;
        this.retirements = retirements;
    }

    @Override
    public String type() {
        return CodeSnapshotJobTypes.CODE_SNAPSHOT_REINDEX;
    }

    @Override
    public void execute(JobExecutionContext context) {
        CodeSnapshotRecord snapshot = requireActive(context);
        Long generationId = lifecycle.insertBuilding(snapshot.id(), context.jobId());
        try {
            CodeSnapshotGenerationResult result = builder.build(context, snapshot, generationId);
            lifecycle.activateReindex(new CodeSnapshotReindexActivation(
                    snapshot.id(), generationId, context.jobId(), snapshot.branchId(),
                    result.indexedFileCount(), result.ignoredFileCount())).ifPresent(retirements::retire);
            context.updateProgress(95);
            context.heartbeat();
        } catch (RuntimeException failure) {
            try {
                lifecycle.failReindex(snapshot.id(), generationId);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private CodeSnapshotRecord requireActive(JobExecutionContext context) {
        CodeSnapshotRecord snapshot = snapshots.findById(context.snapshotId())
                .orElseThrow(CodeSnapshotNotFoundException::new);
        if (snapshot.status() != CodeSnapshotStatus.ACTIVE
                || !snapshot.projectId().equals(context.projectId())
                || !snapshot.branchId().equals(context.branchId())
                || !snapshot.inputObjectKey().equals(context.inputObjectKey())) {
            throw new CodeSnapshotNotActiveException();
        }
        return snapshot;
    }
}
