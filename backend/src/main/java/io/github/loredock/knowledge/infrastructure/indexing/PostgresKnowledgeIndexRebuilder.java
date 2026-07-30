package io.github.loredock.knowledge.infrastructure.indexing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildProgress;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuilder;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentTagEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentTagMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexDocumentEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexDocumentMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexGenerationMapper;
import io.github.loredock.platform.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL 本地知识投影重建器。构建与活动切换使用一个 REPEATABLE READ 事务，不调用外部模型或索引系统。
 */
@Service
public class PostgresKnowledgeIndexRebuilder implements KnowledgeIndexRebuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresKnowledgeIndexRebuilder.class);
    private static final int BATCH_SIZE = 2;

    private final KnowledgeDocumentMapper sourceDocuments;
    private final KnowledgeDocumentTagMapper sourceTags;
    private final KnowledgeIndexGenerationMapper generations;
    private final KnowledgeIndexDocumentMapper projections;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;
    private final TransactionTemplate rebuildTransaction;

    /** 创建只使用 PostgreSQL 的重建器。 */
    public PostgresKnowledgeIndexRebuilder(
            KnowledgeDocumentMapper sourceDocuments,
            KnowledgeDocumentTagMapper sourceTags,
            KnowledgeIndexGenerationMapper generations,
            KnowledgeIndexDocumentMapper projections,
            ObjectMapper objectMapper,
            TimeProvider timeProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.sourceDocuments = sourceDocuments;
        this.sourceTags = sourceTags;
        this.generations = generations;
        this.projections = projections;
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.rebuildTransaction = new TransactionTemplate(transactionManager, definition);
    }

    @Override
    public KnowledgeIndexRebuildResult rebuild(UUID jobId, KnowledgeIndexRebuildProgress progress) {
        if (jobId == null || progress == null) {
            throw new IllegalArgumentException("knowledge rebuild context is required");
        }
        KnowledgeIndexRebuildResult result = rebuildTransaction.execute(status -> rebuildSnapshot(jobId, progress));
        if (result == null) {
            throw new IllegalStateException("knowledge rebuild transaction returned no result");
        }
        cleanupOldRetiredGenerations();
        return result;
    }

    private KnowledgeIndexRebuildResult rebuildSnapshot(UUID jobId, KnowledgeIndexRebuildProgress progress) {
        Instant createdAt = timeProvider.now();
        UUID generationId = UUID.randomUUID();
        generations.insert(KnowledgeIndexGenerationEntity.builder()
                .id(generationId)
                .jobId(jobId)
                .status("BUILDING")
                .documentCount(0L)
                .createdAt(createdAt)
                .activatedAt(null)
                .build());
        long expected = sourceDocuments.selectCount(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getStatus, DocumentStatus.PUBLISHED.name()));
        long written = 0;
        UUID afterId = null;
        while (true) {
            var query = Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                    .eq(KnowledgeDocumentEntity::getStatus, DocumentStatus.PUBLISHED.name())
                    .orderByAsc(KnowledgeDocumentEntity::getId)
                    .last("limit " + BATCH_SIZE);
            if (afterId != null) {
                query.gt(KnowledgeDocumentEntity::getId, afterId);
            }
            List<KnowledgeDocumentEntity> batch = sourceDocuments.selectList(query);
            if (batch.isEmpty()) {
                break;
            }
            for (KnowledgeDocumentEntity document : batch) {
                projections.insertProjection(toProjection(generationId, document));
            }
            written += batch.size();
            afterId = batch.getLast().getId();
            int percentage = expected == 0 ? 90 : Math.min(90, Math.toIntExact(written * 90 / expected));
            progress.update(percentage);
            progress.heartbeat();
        }
        long stored = projections.selectCount(Wrappers.<KnowledgeIndexDocumentEntity>lambdaQuery()
                .eq(KnowledgeIndexDocumentEntity::getGenerationId, generationId));
        if (written != expected || stored != expected) {
            throw new IllegalStateException("knowledge projection count mismatch");
        }

        Instant activatedAt = timeProvider.now();
        generations.update(null, Wrappers.<KnowledgeIndexGenerationEntity>lambdaUpdate()
                .eq(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE")
                .set(KnowledgeIndexGenerationEntity::getStatus, "RETIRED"));
        int activated = generations.update(null, Wrappers.<KnowledgeIndexGenerationEntity>lambdaUpdate()
                .eq(KnowledgeIndexGenerationEntity::getId, generationId)
                .eq(KnowledgeIndexGenerationEntity::getStatus, "BUILDING")
                .set(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE")
                .set(KnowledgeIndexGenerationEntity::getDocumentCount, written)
                .set(KnowledgeIndexGenerationEntity::getActivatedAt, activatedAt));
        if (activated != 1) {
            throw new IllegalStateException("knowledge generation activation failed");
        }
        progress.update(95);
        return new KnowledgeIndexRebuildResult(generationId, written);
    }

    private KnowledgeIndexDocumentEntity toProjection(UUID generationId, KnowledgeDocumentEntity document) {
        List<String> tags = sourceTags.selectList(Wrappers.<KnowledgeDocumentTagEntity>lambdaQuery()
                        .eq(KnowledgeDocumentTagEntity::getDocumentId, document.getId())
                        .orderByAsc(KnowledgeDocumentTagEntity::getNormalizedName))
                .stream().map(KnowledgeDocumentTagEntity::getDisplayName).toList();
        return KnowledgeIndexDocumentEntity.builder()
                .generationId(generationId)
                .documentId(document.getId())
                .sourceRevision(document.getRevision())
                .format(document.getFormat())
                .title(document.getTitle())
                .body(document.getBody())
                .directoryPath(document.getDirectoryPath())
                .tags(objectMapper.writeValueAsString(tags))
                .scopeType(document.getScopeType())
                .projectId(document.getProjectId())
                .branchId(document.getBranchId())
                .sourceType(document.getSourceType())
                .wikiUrl(document.getWikiUrl())
                .originalFilename(document.getOriginalFilename())
                .curationNote(document.getCurationNote())
                .sourceUpdatedAt(document.getUpdatedAt())
                .build();
    }

    private void cleanupOldRetiredGenerations() {
        try {
            List<KnowledgeIndexGenerationEntity> retired = generations.selectList(
                    Wrappers.<KnowledgeIndexGenerationEntity>lambdaQuery()
                            .eq(KnowledgeIndexGenerationEntity::getStatus, "RETIRED")
                            .orderByDesc(KnowledgeIndexGenerationEntity::getActivatedAt)
                            .orderByDesc(KnowledgeIndexGenerationEntity::getCreatedAt));
            // 保留上一个成功 generation 作为诊断证据，更旧数据删除失败不能影响已经提交的 ACTIVE。
            retired.stream().skip(1).forEach(generation -> generations.deleteById(generation.getId()));
        } catch (RuntimeException exception) {
            LOGGER.warn("knowledge_index_retired_generation_cleanup_failed");
        }
    }
}
