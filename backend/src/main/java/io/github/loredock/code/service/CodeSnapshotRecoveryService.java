package io.github.loredock.code.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.code.mapper.CodeIndexGenerationMapper;
import io.github.loredock.code.mapper.CodeSnapshotMapper;
import io.github.loredock.code.model.entity.CodeIndexGenerationEntity;
import io.github.loredock.code.model.entity.CodeSnapshotEntity;
import io.github.loredock.code.model.enums.CodeIndexGenerationStatus;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.job.service.PersistentBackgroundJobService;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用显式实体状态协调后台任务终态、候选快照与 BUILDING generation。 */
@Service
public class CodeSnapshotRecoveryService {

    private final CodeSnapshotMapper snapshots;
    private final CodeIndexGenerationMapper generations;
    private final Predicate<Long> missingOrTerminalJob;

    /** 创建只在启动恢复阶段使用的持久化协调器。 */
    @Autowired
    public CodeSnapshotRecoveryService(
            CodeSnapshotMapper snapshots,
            CodeIndexGenerationMapper generations,
            PersistentBackgroundJobService jobs
    ) {
        this(snapshots, generations, jobs::isMissingOrTerminal);
    }

    /** 允许同包集成测试注入真实查询结果，不为该单一调用另建接口。 */
    protected CodeSnapshotRecoveryService(
            CodeSnapshotMapper snapshots,
            CodeIndexGenerationMapper generations,
            Predicate<Long> missingOrTerminalJob
    ) {
        this.snapshots = snapshots;
        this.generations = generations;
        this.missingOrTerminalJob = missingOrTerminalJob;
    }

    @Transactional
    public Set<Long> reconcileInterruptedBuilds() {
        var building = generations.selectList(Wrappers.<CodeIndexGenerationEntity>lambdaQuery()
                .eq(CodeIndexGenerationEntity::getStatus, CodeIndexGenerationStatus.BUILDING.name()));
        for (CodeIndexGenerationEntity generation : building) {
            if (missingOrTerminalJob.test(generation.getJobId())) {
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
