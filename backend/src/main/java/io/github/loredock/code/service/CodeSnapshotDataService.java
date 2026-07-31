package io.github.loredock.code.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.loredock.code.mapper.CodeSnapshotMapper;
import io.github.loredock.code.model.entity.CodeSnapshotEntity;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.request.AdminCodeSnapshotQuery;
import io.github.loredock.code.model.result.CodeSnapshotAdminPage;
import io.github.loredock.code.model.result.CodeSnapshotAdminView;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.platform.persistence.AuditMetadata;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 使用 MyBatis-Plus 显式实体在调用方事务中保存代码快照。 */
@Service
public class CodeSnapshotDataService {

    private final CodeSnapshotMapper mapper;

    /** @param mapper 代码快照 Mapper */
    public CodeSnapshotDataService(CodeSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    public Long insertCandidate(CodeSnapshotRecord snapshot) {
        CodeSnapshotEntity entity = CodeSnapshotEntity.builder()
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
                .build();
        mapper.insert(entity);
        return Objects.requireNonNull(entity.getId(), "代码快照写入后数据库未回填主键");
    }

    public CodeSnapshotAdminPage listAdmin(AdminCodeSnapshotQuery query) {
        LambdaQueryWrapper<CodeSnapshotEntity> filter = new LambdaQueryWrapper<CodeSnapshotEntity>()
                .eq(query.projectId() != null, CodeSnapshotEntity::getProjectId, query.projectId())
                .eq(query.branchId() != null, CodeSnapshotEntity::getBranchId, query.branchId());
        long total = mapper.selectCount(filter.clone());
        if (total == 0) {
            return new CodeSnapshotAdminPage(java.util.List.of(), query.page(), query.size(), 0, 0);
        }
        int offset = Math.multiplyExact(query.page(), query.size());
        // created_at 可能相同，追加 Long 正序保证翻页期间顺序确定。
        filter.orderByDesc(CodeSnapshotEntity::getCreatedAt)
                .orderByAsc(CodeSnapshotEntity::getId);
        filter.last("limit " + query.size() + " offset " + offset);
        return new CodeSnapshotAdminPage(
                mapper.selectList(filter).stream().map(this::toAdminView).toList(),
                query.page(), query.size(), total, Math.toIntExact((total + query.size() - 1) / query.size()));
    }

    public Optional<CodeSnapshotRecord> findById(Long snapshotId) {
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
