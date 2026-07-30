package io.github.loredock.code.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.loredock.code.application.CodeSnapshotBuildActivation;
import io.github.loredock.code.application.CodeSnapshotLifecyclePort;
import io.github.loredock.code.application.CodeSnapshotReindexActivation;
import io.github.loredock.code.domain.CodeIndexGenerationStatus;
import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.platform.time.TimeProvider;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 MyBatis-Plus 条件更新和 PostgreSQL 分支行锁实现活动切换；文件系统发布不持有数据库事务。
 */
@Repository
public class MybatisPlusCodeSnapshotLifecycleRepository implements CodeSnapshotLifecyclePort {

    private final CodeSnapshotMapper snapshots;
    private final CodeIndexGenerationMapper generations;
    private final CodeSnapshotLifecycleMapper locks;
    private final TransactionTemplate transaction;
    private final TimeProvider timeProvider;

    /** 生产构造器。 */
    @Autowired
    public MybatisPlusCodeSnapshotLifecycleRepository(
            CodeSnapshotMapper snapshots,
            CodeIndexGenerationMapper generations,
            CodeSnapshotLifecycleMapper locks,
            PlatformTransactionManager transactionManager,
            TimeProvider timeProvider
    ) {
        this.snapshots = snapshots;
        this.generations = generations;
        this.locks = locks;
        this.transaction = new TransactionTemplate(transactionManager);
        this.timeProvider = timeProvider;
    }

    @Override
    public void insertBuilding(UUID generationId, UUID snapshotId, UUID jobId) {
        Instant now = timeProvider.now();
        generations.insert(CodeIndexGenerationEntity.builder()
                .id(generationId).snapshotId(snapshotId).jobId(jobId)
                .status(CodeIndexGenerationStatus.BUILDING.name()).documentCount(0L)
                .createdAt(now).build());
    }

    @Override
    public Optional<UUID> activateBuild(CodeSnapshotBuildActivation activation) {
        return Objects.requireNonNull(transaction.execute(status -> activateInTransaction(activation)),
                "code snapshot activation transaction did not complete");
    }

    @Override
    public void failBuild(UUID snapshotId, UUID generationId) {
        transaction.executeWithoutResult(status -> {
            Instant now = timeProvider.now();
            snapshots.update(null, new LambdaUpdateWrapper<CodeSnapshotEntity>()
                    .eq(CodeSnapshotEntity::getId, snapshotId)
                    .eq(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.CANDIDATE.name())
                    .set(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.FAILED.name())
                    .set(CodeSnapshotEntity::getUpdatedAt, now));
            generations.update(null, new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                    .eq(CodeIndexGenerationEntity::getId, generationId)
                    .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name())
                    .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.FAILED.name()));
        });
    }

    @Override
    public Optional<UUID> activateReindex(CodeSnapshotReindexActivation activation) {
        return Objects.requireNonNull(transaction.execute(status -> activateReindexInTransaction(activation)),
                "code snapshot reindex transaction did not complete");
    }

    @Override
    public void failReindex(UUID snapshotId, UUID generationId) {
        transaction.executeWithoutResult(status -> generations.update(null,
                new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                        .eq(CodeIndexGenerationEntity::getId, generationId)
                        .eq(CodeIndexGenerationEntity::getSnapshotId, snapshotId)
                        .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name())
                        .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.FAILED.name())));
    }

    private Optional<UUID> activateInTransaction(CodeSnapshotBuildActivation activation) {
        lockBranch(activation.branchId());
        CodeSnapshotEntity candidate = snapshots.selectById(activation.snapshotId());
        CodeIndexGenerationEntity generation = generations.selectById(activation.generationId());
        if (candidate == null || generation == null
                || !CodeSnapshotStatus.CANDIDATE.name().equals(candidate.getStatus())
                || !CodeIndexGenerationStatus.BUILDING.name().equals(generation.getStatus())
                || !activation.branchId().equals(candidate.getBranchId())
                || !activation.snapshotId().equals(generation.getSnapshotId())
                || !activation.jobId().equals(generation.getJobId())) {
            throw new IllegalStateException("candidate generation activation scope mismatch");
        }
        CodeSnapshotEntity previous = snapshots.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CodeSnapshotEntity>()
                .eq(CodeSnapshotEntity::getBranchId, activation.branchId())
                .eq(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.ACTIVE.name()));
        Instant now = timeProvider.now();
        UUID retiredGenerationId = null;
        if (previous != null) {
            CodeIndexGenerationEntity previousGeneration = generations.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CodeIndexGenerationEntity>()
                            .eq(CodeIndexGenerationEntity::getSnapshotId, previous.getId())
                            .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name()));
            if (previousGeneration == null) {
                throw new IllegalStateException("active snapshot generation is missing");
            }
            retiredGenerationId = previousGeneration.getId();
            generations.update(null, new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                    .eq(CodeIndexGenerationEntity::getId, retiredGenerationId)
                    .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name())
                    .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.RETIRED.name()));
            snapshots.update(null, new LambdaUpdateWrapper<CodeSnapshotEntity>()
                    .eq(CodeSnapshotEntity::getId, previous.getId())
                    .eq(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.ACTIVE.name())
                    .set(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.RETIRED.name())
                    .set(CodeSnapshotEntity::getUpdatedAt, now));
        }
        int snapshotUpdated = snapshots.update(null, new LambdaUpdateWrapper<CodeSnapshotEntity>()
                .eq(CodeSnapshotEntity::getId, activation.snapshotId())
                .eq(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.CANDIDATE.name())
                .set(CodeSnapshotEntity::getPreviousSnapshotId, previous == null ? null : previous.getId())
                .set(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.ACTIVE.name())
                .set(CodeSnapshotEntity::getIndexedFileCount, activation.indexedFileCount())
                .set(CodeSnapshotEntity::getIgnoredFileCount, activation.ignoredFileCount())
                .set(CodeSnapshotEntity::getIndexedAt, now)
                .set(CodeSnapshotEntity::getUpdatedAt, now));
        int generationUpdated = generations.update(null, new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                .eq(CodeIndexGenerationEntity::getId, activation.generationId())
                .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name())
                .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name())
                .set(CodeIndexGenerationEntity::getDocumentCount, activation.indexedFileCount())
                .set(CodeIndexGenerationEntity::getActivatedAt, now));
        if (snapshotUpdated != 1 || generationUpdated != 1) {
            throw new IllegalStateException("candidate generation activation lost update");
        }
        return Optional.ofNullable(retiredGenerationId);
    }

    private Optional<UUID> activateReindexInTransaction(CodeSnapshotReindexActivation activation) {
        lockBranch(activation.branchId());
        CodeSnapshotEntity snapshot = snapshots.selectById(activation.snapshotId());
        CodeIndexGenerationEntity candidate = generations.selectById(activation.generationId());
        if (snapshot == null || candidate == null
                || !CodeSnapshotStatus.ACTIVE.name().equals(snapshot.getStatus())
                || !activation.branchId().equals(snapshot.getBranchId())
                || !CodeIndexGenerationStatus.BUILDING.name().equals(candidate.getStatus())
                || !activation.snapshotId().equals(candidate.getSnapshotId())
                || !activation.jobId().equals(candidate.getJobId())) {
            throw new IllegalStateException("reindex generation activation scope mismatch");
        }
        CodeIndexGenerationEntity previous = generations.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CodeIndexGenerationEntity>()
                        .eq(CodeIndexGenerationEntity::getSnapshotId, activation.snapshotId())
                        .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name()));
        if (previous == null) {
            throw new IllegalStateException("active generation is missing during reindex");
        }
        Instant now = timeProvider.now();
        int retired = generations.update(null, new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                .eq(CodeIndexGenerationEntity::getId, previous.getId())
                .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name())
                .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.RETIRED.name()));
        int activated = generations.update(null, new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                .eq(CodeIndexGenerationEntity::getId, activation.generationId())
                .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name())
                .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name())
                .set(CodeIndexGenerationEntity::getDocumentCount, activation.indexedFileCount())
                .set(CodeIndexGenerationEntity::getActivatedAt, now));
        int refreshed = snapshots.update(null, new LambdaUpdateWrapper<CodeSnapshotEntity>()
                .eq(CodeSnapshotEntity::getId, activation.snapshotId())
                .eq(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.ACTIVE.name())
                .set(CodeSnapshotEntity::getIndexedFileCount, activation.indexedFileCount())
                .set(CodeSnapshotEntity::getIgnoredFileCount, activation.ignoredFileCount())
                .set(CodeSnapshotEntity::getIndexedAt, now)
                .set(CodeSnapshotEntity::getUpdatedAt, now));
        if (retired != 1 || activated != 1 || refreshed != 1) {
            throw new IllegalStateException("reindex generation activation lost update");
        }
        return Optional.of(previous.getId());
    }

    private void lockBranch(UUID branchId) {
        if (!branchId.equals(locks.lockBranch(branchId))) {
            throw new IllegalStateException("branch disappeared during activation");
        }
    }
}
