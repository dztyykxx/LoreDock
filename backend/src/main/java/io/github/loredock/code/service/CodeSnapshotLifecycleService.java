package io.github.loredock.code.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.loredock.code.mapper.CodeIndexGenerationMapper;
import io.github.loredock.code.mapper.CodeSnapshotLifecycleMapper;
import io.github.loredock.code.mapper.CodeSnapshotMapper;
import io.github.loredock.code.model.entity.CodeIndexGenerationEntity;
import io.github.loredock.code.model.entity.CodeSnapshotEntity;
import io.github.loredock.code.model.enums.CodeIndexGenerationStatus;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.result.CodeSnapshotBuildActivation;
import io.github.loredock.code.model.result.CodeSnapshotReindexActivation;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用 MyBatis-Plus 条件更新和 PostgreSQL 分支行锁实现活动切换；文件系统发布不持有数据库事务。
 */
@Service
public class CodeSnapshotLifecycleService {

    private final CodeSnapshotMapper snapshots;
    private final CodeIndexGenerationMapper generations;
    private final CodeSnapshotLifecycleMapper locks;
    private final TransactionTemplate transaction;
    private final Clock timeProvider;

    /** 生产构造器。 */
    @Autowired
    public CodeSnapshotLifecycleService(
            CodeSnapshotMapper snapshots,
            CodeIndexGenerationMapper generations,
            CodeSnapshotLifecycleMapper locks,
            PlatformTransactionManager transactionManager,
            Clock timeProvider
    ) {
        this.snapshots = snapshots;
        this.generations = generations;
        this.locks = locks;
        this.transaction = new TransactionTemplate(transactionManager);
        this.timeProvider = timeProvider;
    }

    public Long insertBuilding(Long snapshotId, Long jobId) {
        Instant now = timeProvider.instant();
        CodeIndexGenerationEntity generation = CodeIndexGenerationEntity.builder()
                .snapshotId(snapshotId).jobId(jobId)
                .status(CodeIndexGenerationStatus.BUILDING.name()).documentCount(0L)
                .createdAt(now).build();
        generations.insert(generation);
        return Objects.requireNonNull(generation.getId(), "代码索引 generation 写入后数据库未回填主键");
    }

    public Optional<Long> activateBuild(CodeSnapshotBuildActivation activation) {
        return Objects.requireNonNull(transaction.execute(status -> activateInTransaction(activation)),
                "code snapshot activation transaction did not complete");
    }

    public void failBuild(Long snapshotId, Long generationId) {
        transaction.executeWithoutResult(status -> {
            Instant now = timeProvider.instant();
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

    public Optional<Long> activateReindex(CodeSnapshotReindexActivation activation) {
        return Objects.requireNonNull(transaction.execute(status -> activateReindexInTransaction(activation)),
                "code snapshot reindex transaction did not complete");
    }

    public void failReindex(Long snapshotId, Long generationId) {
        transaction.executeWithoutResult(status -> generations.update(null,
                new LambdaUpdateWrapper<CodeIndexGenerationEntity>()
                        .eq(CodeIndexGenerationEntity::getId, generationId)
                        .eq(CodeIndexGenerationEntity::getSnapshotId, snapshotId)
                        .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name())
                        .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.FAILED.name())));
    }

    private Optional<Long> activateInTransaction(CodeSnapshotBuildActivation activation) {
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
        Instant now = timeProvider.instant();
        Long retiredGenerationId = null;
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

    private Optional<Long> activateReindexInTransaction(CodeSnapshotReindexActivation activation) {
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
        Instant now = timeProvider.instant();
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

    private void lockBranch(Long branchId) {
        if (!branchId.equals(locks.lockBranch(branchId))) {
            throw new IllegalStateException("branch disappeared during activation");
        }
    }
}
