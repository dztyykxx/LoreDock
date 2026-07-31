package io.github.loredock.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.mapper.KnowledgeImportBatchMapper;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.entity.KnowledgeImportBatchEntity;
import io.github.loredock.knowledge.model.enums.ImportBatchStatus;
import io.github.loredock.knowledge.model.enums.ImportItemReason;
import io.github.loredock.knowledge.model.enums.ImportItemStatus;
import io.github.loredock.knowledge.model.snapshot.KnowledgeImportBatchRecord;
import io.github.loredock.knowledge.model.snapshot.KnowledgeImportItemRecord;
import io.github.loredock.platform.persistence.AuditMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis-Plus 导入证据仓储；所有实体字段均显式映射，明细读取固定按 ordinal 排序。 */
@Service
public class KnowledgeImportDataService {

    private final KnowledgeImportBatchMapper batchMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param batchMapper 批次 Mapper
     * @param objectMapper 批次结果 JSON 编解码器
     */
    public KnowledgeImportDataService(
            KnowledgeImportBatchMapper batchMapper,
            ObjectMapper objectMapper
    ) {
        this.batchMapper = batchMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long insertBatch(KnowledgeImportBatchRecord batch) {
        KnowledgeImportBatchEntity entity = KnowledgeImportBatchEntity.builder()
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
                .resultsJson("[]")
                .createdAt(batch.audit().createdAt())
                .updatedAt(batch.audit().updatedAt())
                .createdBy(batch.audit().createdBy())
                .updatedBy(batch.audit().updatedBy())
                .build();
        batchMapper.insert(entity);
        return Objects.requireNonNull(entity.getId(), "知识导入批次写入后数据库未回填主键");
    }

    @Transactional
    public void updateSummary(
            Long batchId,
            ImportBatchStatus status,
            int succeeded,
            int failed,
            int ignored,
            List<KnowledgeImportItemRecord> results,
            Instant updatedAt,
            String updatedBy
    ) {
        batchMapper.update(null, Wrappers.<KnowledgeImportBatchEntity>lambdaUpdate()
                .set(KnowledgeImportBatchEntity::getStatus, status.name())
                .set(KnowledgeImportBatchEntity::getSucceededCount, succeeded)
                .set(KnowledgeImportBatchEntity::getFailedCount, failed)
                .set(KnowledgeImportBatchEntity::getIgnoredCount, ignored)
                .set(KnowledgeImportBatchEntity::getResultsJson, serializeResults(results))
                .set(KnowledgeImportBatchEntity::getUpdatedAt, updatedAt)
                .set(KnowledgeImportBatchEntity::getUpdatedBy, updatedBy)
                .eq(KnowledgeImportBatchEntity::getId, batchId));
    }

    @Transactional(readOnly = true)
    public Optional<KnowledgeImportBatchRecord> findBatch(Long batchId) {
        return Optional.ofNullable(batchMapper.selectById(batchId)).map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeImportItemRecord> findItems(Long batchId) {
        KnowledgeImportBatchEntity batch = batchMapper.selectById(batchId);
        return batch == null ? List.of() : deserializeResults(batch.getResultsJson()).stream()
                .sorted(java.util.Comparator.comparingInt(KnowledgeImportItemRecord::ordinal)).toList();
    }

    @Transactional(readOnly = true)
    public boolean isScopeValid(KnowledgeScope scope) {
        return switch (scope.type()) {
            case GLOBAL -> true;
            case PROJECT -> batchMapper.projectExists(scope.projectId());
            case BRANCH -> batchMapper.branchBelongsToProject(scope.projectId(), scope.branchId());
        };
    }

    private KnowledgeImportBatchRecord toRecord(KnowledgeImportBatchEntity entity) {
        KnowledgeScope scope = switch (io.github.loredock.knowledge.model.enums.KnowledgeScopeType.valueOf(
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

    private String serializeResults(List<KnowledgeImportItemRecord> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识导入结果序列化失败", exception);
        }
    }

    private List<KnowledgeImportItemRecord> deserializeResults(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<KnowledgeImportItemRecord>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识导入结果读取失败", exception);
        }
    }
}
