package io.github.loredock.code.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.loredock.code.application.AdminCodeSnapshotQuery;
import io.github.loredock.code.application.CodeSnapshotAdminPage;
import io.github.loredock.code.application.CodeSnapshotAdminView;
import io.github.loredock.code.application.CodeSnapshotRecord;
import io.github.loredock.code.application.CodeSnapshotRepository;
import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.platform.audit.AuditMetadata;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** 使用 MyBatis-Plus 显式实体在调用方事务中保存代码快照。 */
@Repository
public class MybatisPlusCodeSnapshotRepository implements CodeSnapshotRepository {

    private final CodeSnapshotMapper mapper;

    /** @param mapper 代码快照 Mapper */
    public MybatisPlusCodeSnapshotRepository(CodeSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertCandidate(CodeSnapshotRecord snapshot) {
        mapper.insert(CodeSnapshotEntity.builder()
                .id(snapshot.id())
                .projectId(snapshot.projectId())
                .branchId(snapshot.branchId())
                .commitHash(snapshot.commit())
                .inputObjectKey(snapshot.inputObjectKey())
                .status(snapshot.status().name())
                .previousSnapshotId(snapshot.previousSnapshotId())
                .indexedFileCount(snapshot.indexedFileCount())
                .ignoredFileCount(snapshot.ignoredFileCount())
                .indexedAt(snapshot.indexedAt())
                .createdAt(snapshot.audit().createdAt())
                .updatedAt(snapshot.audit().updatedAt())
                .createdBy(snapshot.audit().createdBy())
                .updatedBy(snapshot.audit().updatedBy())
                .build());
    }

    @Override
    public CodeSnapshotAdminPage listAdmin(AdminCodeSnapshotQuery query) {
        LambdaQueryWrapper<CodeSnapshotEntity> filter = new LambdaQueryWrapper<CodeSnapshotEntity>()
                .eq(query.projectId() != null, CodeSnapshotEntity::getProjectId, query.projectId())
                .eq(query.branchId() != null, CodeSnapshotEntity::getBranchId, query.branchId());
        long total = mapper.selectCount(filter.clone());
        if (total == 0) {
            return new CodeSnapshotAdminPage(java.util.List.of(), query.page(), query.size(), 0, 0);
        }
        int offset = Math.multiplyExact(query.page(), query.size());
        // created_at 可能相同，追加 UUID 正序保证翻页期间顺序确定。
        filter.orderByDesc(CodeSnapshotEntity::getCreatedAt)
                .orderByAsc(CodeSnapshotEntity::getId);
        filter.last("limit " + query.size() + " offset " + offset);
        return new CodeSnapshotAdminPage(
                mapper.selectList(filter).stream().map(this::toAdminView).toList(),
                query.page(), query.size(), total, Math.toIntExact((total + query.size() - 1) / query.size()));
    }

    @Override
    public Optional<CodeSnapshotRecord> findById(UUID snapshotId) {
        return Optional.ofNullable(mapper.selectById(snapshotId)).map(this::toRecord);
    }

    private CodeSnapshotAdminView toAdminView(CodeSnapshotEntity entity) {
        return new CodeSnapshotAdminView(
                entity.getId(), entity.getProjectId(), entity.getBranchId(), entity.getCommitHash(),
                CodeSnapshotStatus.valueOf(entity.getStatus()), entity.getIndexedFileCount(),
                entity.getIgnoredFileCount(), entity.getIndexedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private CodeSnapshotRecord toRecord(CodeSnapshotEntity entity) {
        return new CodeSnapshotRecord(
                entity.getId(), entity.getProjectId(), entity.getBranchId(), entity.getCommitHash(),
                entity.getInputObjectKey(), CodeSnapshotStatus.valueOf(entity.getStatus()),
                entity.getPreviousSnapshotId(), entity.getIndexedFileCount(), entity.getIgnoredFileCount(),
                entity.getIndexedAt(), new AuditMetadata(
                        entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCreatedBy(), entity.getUpdatedBy()));
    }
}
