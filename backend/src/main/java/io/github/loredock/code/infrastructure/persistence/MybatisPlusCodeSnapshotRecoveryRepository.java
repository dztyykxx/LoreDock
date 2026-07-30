package io.github.loredock.code.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.code.application.CodeSnapshotRecoveryRepository;
import io.github.loredock.code.domain.CodeIndexGenerationStatus;
import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.job.domain.JobStatus;
import io.github.loredock.job.infrastructure.persistence.BackgroundJobEntity;
import io.github.loredock.job.infrastructure.persistence.BackgroundJobMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 使用显式实体状态协调后台任务终态、候选快照与 BUILDING generation。 */
@Repository
public class MybatisPlusCodeSnapshotRecoveryRepository implements CodeSnapshotRecoveryRepository {

    private static final Set<JobStatus> TERMINAL = EnumSet.of(
            JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED);

    private final CodeSnapshotMapper snapshots;
    private final CodeIndexGenerationMapper generations;
    private final BackgroundJobMapper jobs;

    /** 创建只在启动恢复阶段使用的持久化协调器。 */
    public MybatisPlusCodeSnapshotRecoveryRepository(
            CodeSnapshotMapper snapshots,
            CodeIndexGenerationMapper generations,
            BackgroundJobMapper jobs
    ) {
        this.snapshots = snapshots;
        this.generations = generations;
        this.jobs = jobs;
    }

    @Override
    @Transactional
    public Set<UUID> reconcileInterruptedBuilds() {
        var building = generations.selectList(Wrappers.<CodeIndexGenerationEntity>lambdaQuery()
                .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name()));
        for (CodeIndexGenerationEntity generation : building) {
            BackgroundJobEntity job = jobs.selectById(generation.getJobId());
            if (job == null || TERMINAL.contains(JobStatus.valueOf(job.getStatus()))) {
                generations.update(null, Wrappers.<CodeIndexGenerationEntity>lambdaUpdate()
                        .eq(CodeIndexGenerationEntity::getId, generation.getId())
                        .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name())
                        .set(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.FAILED.name()));
                snapshots.update(null, Wrappers.<CodeSnapshotEntity>lambdaUpdate()
                        .eq(CodeSnapshotEntity::getId, generation.getSnapshotId())
                        .eq(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.CANDIDATE.name())
                        .set(CodeSnapshotEntity::getStatus, CodeSnapshotStatus.FAILED.name()));
            }
        }
        return generations.selectList(Wrappers.<CodeIndexGenerationEntity>lambdaQuery()
                        .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.ACTIVE.name()))
                .stream().map(CodeIndexGenerationEntity::getId).collect(Collectors.toUnmodifiableSet());
    }
}
