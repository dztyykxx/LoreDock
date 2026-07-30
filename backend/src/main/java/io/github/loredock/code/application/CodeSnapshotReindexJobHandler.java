package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.job.application.JobExecutionContext;
import io.github.loredock.job.application.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 活动快照重建处理器；任何失败只终结新 generation，不改变当前活动查询入口。 */
@Component
public class CodeSnapshotReindexJobHandler implements JobHandler {

    private final CodeSnapshotRepository snapshots;
    private final CodeSnapshotLifecyclePort lifecycle;
    private final CodeSnapshotGenerationBuilder builder;
    private final CodeIndexRetirementPort retirements;

    /** 创建复用同一安全索引流水线的重建处理器。 */
    public CodeSnapshotReindexJobHandler(
            CodeSnapshotRepository snapshots,
            CodeSnapshotLifecyclePort lifecycle,
            CodeSnapshotGenerationBuilder builder,
            CodeIndexRetirementPort retirements
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
        UUID generationId = UUID.randomUUID();
        lifecycle.insertBuilding(generationId, snapshot.id(), context.jobId());
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
