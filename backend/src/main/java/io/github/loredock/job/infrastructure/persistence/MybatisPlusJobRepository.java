package io.github.loredock.job.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.job.application.JobRepository;
import io.github.loredock.job.domain.BackgroundJob;
import io.github.loredock.job.domain.JobSnapshot;
import io.github.loredock.job.domain.JobStatus;
import io.github.loredock.platform.audit.AuditMetadata;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 MyBatis-Plus Java Wrapper 保存和条件更新任务；期望状态条件防止并发终态互相覆盖。
 */
@Repository
public class MybatisPlusJobRepository implements JobRepository {

    private final BackgroundJobMapper mapper;

    /**
     * @param mapper 后台任务 Mapper
     */
    public MybatisPlusJobRepository(BackgroundJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertPending(BackgroundJob job, AuditMetadata audit) {
        JobSnapshot snapshot = job.snapshot();
        mapper.insert(BackgroundJobEntity.builder()
                .id(snapshot.id())
                .jobType(snapshot.type())
                .status(snapshot.status().name())
                .progress(snapshot.progress())
                .inputObjectKey(snapshot.inputObjectKey())
                .startedAt(snapshot.startedAt())
                .finishedAt(snapshot.finishedAt())
                .heartbeatAt(snapshot.heartbeatAt())
                .ownerInstance(snapshot.ownerInstance())
                .errorCode(snapshot.errorCode())
                .errorMessage(snapshot.errorMessage())
                .createdAt(audit.createdAt())
                .updatedAt(audit.updatedAt())
                .createdBy(audit.createdBy())
                .updatedBy(audit.updatedBy())
                .build());
    }

    @Override
    public Optional<BackgroundJob> find(UUID jobId) {
        return Optional.ofNullable(mapper.selectById(jobId))
                .map(this::toDomain);
    }

    @Override
    public Optional<BackgroundJob> findActiveByType(String type) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<BackgroundJobEntity>lambdaQuery()
                        .eq(BackgroundJobEntity::getJobType, type)
                        .in(BackgroundJobEntity::getStatus, JobStatus.PENDING.name(), JobStatus.RUNNING.name())
                        .orderByAsc(BackgroundJobEntity::getCreatedAt)
                        .orderByAsc(BackgroundJobEntity::getId)
                        .last("limit 1")))
                .map(this::toDomain);
    }

    @Override
    public boolean update(BackgroundJob job, JobStatus expectedStatus, Instant updatedAt, String updatedBy) {
        JobSnapshot snapshot = job.snapshot();
        int updated = mapper.update(Wrappers.<BackgroundJobEntity>lambdaUpdate()
                .eq(BackgroundJobEntity::getId, snapshot.id())
                .eq(BackgroundJobEntity::getStatus, expectedStatus.name())
                .set(BackgroundJobEntity::getStatus, snapshot.status().name())
                .set(BackgroundJobEntity::getProgress, snapshot.progress())
                .set(BackgroundJobEntity::getStartedAt, snapshot.startedAt())
                .set(BackgroundJobEntity::getFinishedAt, snapshot.finishedAt())
                .set(BackgroundJobEntity::getHeartbeatAt, snapshot.heartbeatAt())
                .set(BackgroundJobEntity::getOwnerInstance, snapshot.ownerInstance())
                .set(BackgroundJobEntity::getErrorCode, snapshot.errorCode())
                .set(BackgroundJobEntity::getErrorMessage, snapshot.errorMessage())
                .set(BackgroundJobEntity::getUpdatedAt, updatedAt)
                .set(BackgroundJobEntity::getUpdatedBy, updatedBy));
        return updated == 1;
    }

    @Override
    public int failStaleRunning(Instant heartbeatBefore, Instant recoveredAt, String updatedBy) {
        return mapper.update(Wrappers.<BackgroundJobEntity>lambdaUpdate()
                .eq(BackgroundJobEntity::getStatus, JobStatus.RUNNING.name())
                .lt(BackgroundJobEntity::getHeartbeatAt, heartbeatBefore)
                .set(BackgroundJobEntity::getStatus, JobStatus.FAILED.name())
                .set(BackgroundJobEntity::getFinishedAt, recoveredAt)
                .set(BackgroundJobEntity::getErrorCode, "PROCESS_INTERRUPTED")
                .set(BackgroundJobEntity::getErrorMessage, "上次执行进程中断，任务未自动重放")
                .set(BackgroundJobEntity::getUpdatedAt, recoveredAt)
                .set(BackgroundJobEntity::getUpdatedBy, updatedBy));
    }

    private BackgroundJob toDomain(BackgroundJobEntity entity) {
        return BackgroundJob.restore(new JobSnapshot(
                entity.getId(),
                entity.getJobType(),
                JobStatus.valueOf(entity.getStatus()),
                entity.getProgress(),
                entity.getInputObjectKey(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getHeartbeatAt(),
                entity.getOwnerInstance(),
                entity.getErrorCode(),
                entity.getErrorMessage()
        ));
    }
}
