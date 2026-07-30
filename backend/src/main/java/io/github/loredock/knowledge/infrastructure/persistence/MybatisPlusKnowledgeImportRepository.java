package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.knowledge.application.KnowledgeImportBatchRecord;
import io.github.loredock.knowledge.application.KnowledgeImportItemRecord;
import io.github.loredock.knowledge.application.KnowledgeImportRepository;
import io.github.loredock.knowledge.domain.ImportBatchStatus;
import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.platform.audit.AuditMetadata;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MyBatis-Plus 导入证据仓储；所有实体字段均显式映射，明细读取固定按 ordinal 排序。 */
@Repository
public class MybatisPlusKnowledgeImportRepository implements KnowledgeImportRepository {

    private final KnowledgeImportBatchMapper batchMapper;
    private final KnowledgeImportItemMapper itemMapper;

    /**
     * @param batchMapper 批次 Mapper
     * @param itemMapper 条目 Mapper
     */
    public MybatisPlusKnowledgeImportRepository(
            KnowledgeImportBatchMapper batchMapper,
            KnowledgeImportItemMapper itemMapper
    ) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    @Transactional
    public void insertBatch(KnowledgeImportBatchRecord batch) {
        batchMapper.insert(KnowledgeImportBatchEntity.builder()
                .id(batch.id())
                .objectKey(batch.objectKey())
                .originalFilename(batch.originalFilename())
                .scopeType(batch.scope().type().name())
                .projectId(batch.scope().projectId())
                .branchId(batch.scope().branchId())
                .directoryPrefix(batch.directoryPrefix())
                .status(batch.status().name())
                .succeededCount(batch.succeededCount())
                .failedCount(batch.failedCount())
                .ignoredCount(batch.ignoredCount())
                .createdAt(batch.audit().createdAt())
                .updatedAt(batch.audit().updatedAt())
                .createdBy(batch.audit().createdBy())
                .updatedBy(batch.audit().updatedBy())
                .build());
    }

    @Override
    public void insertItem(KnowledgeImportItemRecord item) {
        itemMapper.insert(KnowledgeImportItemEntity.builder()
                .id(item.id())
                .batchId(item.batchId())
                .ordinal(item.ordinal())
                .entryName(item.entryName())
                .status(item.status().name())
                .reasonCode(item.reason().name())
                .message(item.message())
                .documentId(item.documentId())
                .build());
    }

    @Override
    @Transactional
    public void updateSummary(
            UUID batchId,
            ImportBatchStatus status,
            int succeeded,
            int failed,
            int ignored,
            Instant updatedAt,
            String updatedBy
    ) {
        batchMapper.update(null, Wrappers.<KnowledgeImportBatchEntity>lambdaUpdate()
                .set(KnowledgeImportBatchEntity::getStatus, status.name())
                .set(KnowledgeImportBatchEntity::getSucceededCount, succeeded)
                .set(KnowledgeImportBatchEntity::getFailedCount, failed)
                .set(KnowledgeImportBatchEntity::getIgnoredCount, ignored)
                .set(KnowledgeImportBatchEntity::getUpdatedAt, updatedAt)
                .set(KnowledgeImportBatchEntity::getUpdatedBy, updatedBy)
                .eq(KnowledgeImportBatchEntity::getId, batchId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeImportBatchRecord> findBatch(UUID batchId) {
        return Optional.ofNullable(batchMapper.selectById(batchId)).map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeImportItemRecord> findItems(UUID batchId) {
        return itemMapper.selectList(Wrappers.<KnowledgeImportItemEntity>lambdaQuery()
                        .eq(KnowledgeImportItemEntity::getBatchId, batchId)
                        .orderByAsc(KnowledgeImportItemEntity::getOrdinal))
                .stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isScopeValid(KnowledgeScope scope) {
        return switch (scope.type()) {
            case GLOBAL -> true;
            case PROJECT -> batchMapper.projectExists(scope.projectId());
            case BRANCH -> batchMapper.branchBelongsToProject(scope.projectId(), scope.branchId());
        };
    }

    private KnowledgeImportBatchRecord toRecord(KnowledgeImportBatchEntity entity) {
        KnowledgeScope scope = switch (io.github.loredock.knowledge.domain.KnowledgeScopeType.valueOf(
                entity.getScopeType())) {
            case GLOBAL -> KnowledgeScope.global();
            case PROJECT -> KnowledgeScope.project(entity.getProjectId());
            case BRANCH -> KnowledgeScope.branch(entity.getProjectId(), entity.getBranchId());
        };
        return new KnowledgeImportBatchRecord(
                entity.getId(), entity.getObjectKey(), entity.getOriginalFilename(), scope,
                entity.getDirectoryPrefix(), ImportBatchStatus.valueOf(entity.getStatus()),
                entity.getSucceededCount(), entity.getFailedCount(), entity.getIgnoredCount(),
                new AuditMetadata(entity.getCreatedAt(), entity.getUpdatedAt(),
                        entity.getCreatedBy(), entity.getUpdatedBy()));
    }

    private KnowledgeImportItemRecord toRecord(KnowledgeImportItemEntity entity) {
        return new KnowledgeImportItemRecord(
                entity.getId(), entity.getBatchId(), entity.getOrdinal(), entity.getEntryName(),
                ImportItemStatus.valueOf(entity.getStatus()), ImportItemReason.valueOf(entity.getReasonCode()),
                entity.getMessage(), entity.getDocumentId());
    }
}
