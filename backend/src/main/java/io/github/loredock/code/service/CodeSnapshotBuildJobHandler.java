package io.github.loredock.code.service;

import io.github.loredock.code.config.CodeSnapshotJobTypes;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotBuildActivation;
import io.github.loredock.code.model.result.CodeSnapshotGenerationResult;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.code.service.index.LuceneIndexHandleRegistry;
import io.github.loredock.job.service.JobExecutionContext;
import io.github.loredock.job.service.JobHandler;
import org.springframework.stereotype.Component;

/**
 * 代码快照构建处理器：ZIP 全包校验后逐文件选择，发布独立 generation，最后用短事务切换活动入口。
 */
@Component
public class CodeSnapshotBuildJobHandler implements JobHandler {

    private final CodeSnapshotDataService snapshots;
    private final CodeSnapshotLifecycleService lifecycle;
    private final CodeSnapshotGenerationBuilder builder;
    private final LuceneIndexHandleRegistry retirements;

    /**
     * @param snapshots 候选快照读取端口
     * @param lifecycle generation 与活动切换端口
     * @param builder 构建与重建共用的安全索引流水线
     * @param retirements 提交后延迟关闭旧 reader 的端口
     */
    public CodeSnapshotBuildJobHandler(
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
        return CodeSnapshotJobTypes.CODE_SNAPSHOT_BUILD;
    }

    @Override
    public void execute(JobExecutionContext context) {
        CodeSnapshotRecord snapshot = requireCandidate(context);
        Long generationId = lifecycle.insertBuilding(snapshot.id(), context.jobId());
        try {
            CodeSnapshotGenerationResult result = builder.build(context, snapshot, generationId);
            lifecycle.activateBuild(new CodeSnapshotBuildActivation(
                    snapshot.id(), generationId, context.jobId(), snapshot.branchId(),
                    result.indexedFileCount(), result.ignoredFileCount())).ifPresent(retirements::retire);
            context.updateProgress(95);
            context.heartbeat();
        } catch (RuntimeException failure) {
            // BUILD 失败只终结本次候选，旧活动快照和 generation 由数据库条件更新明确保护。
            try {
                lifecycle.failBuild(snapshot.id(), generationId);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private CodeSnapshotRecord requireCandidate(JobExecutionContext context) {
        CodeSnapshotRecord snapshot = snapshots.findById(context.snapshotId())
                .orElseThrow(() -> new IllegalArgumentException("code snapshot job scope is missing"));
        if (snapshot.status() != CodeSnapshotStatus.CANDIDATE
                || !snapshot.projectId().equals(context.projectId())
                || !snapshot.branchId().equals(context.branchId())
                || !snapshot.inputObjectKey().equals(context.inputObjectKey())) {
            throw new IllegalArgumentException("code snapshot job scope mismatch");
        }
        return snapshot;
    }

}
