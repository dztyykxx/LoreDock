package io.github.loredock.knowledge.infrastructure.indexing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildProgress;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.application.KnowledgeIndexRebuilder;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingPort;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.application.search.ReciprocalRankFusion;
import io.github.loredock.knowledge.application.search.indexing.AnalyzedKnowledgeText;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeChunk;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeChunker;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeSearchChunkWrite;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeSearchGenerationMetadata;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeSearchIndexRepository;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeTextAnalyzer;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentTagEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentTagMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexDocumentEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexDocumentMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexGenerationMapper;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeSearchChunkMapper;
import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.platform.web.ApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL 知识搜索分阶段重建器。
 *
 * <p>第一阶段只在短 {@code REPEATABLE READ} 事务中冻结 PUBLISHED 投影；分块、分析和
 * Embedding 全部在事务外执行并分批短事务写入。只有计数、序号、关键词与向量完整性通过后，
 * 才在另一个短事务中退休旧 ACTIVE 并激活新 generation。</p>
 */
@Service
public class PostgresKnowledgeIndexRebuilder implements KnowledgeIndexRebuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresKnowledgeIndexRebuilder.class);
    private static final int SNAPSHOT_BATCH_SIZE = 100;
    private static final int EMBEDDING_BATCH_SIZE = 16;
    private static final int VECTOR_DIMENSION = 512;

    private final KnowledgeDocumentMapper sourceDocuments;
    private final KnowledgeDocumentTagMapper sourceTags;
    private final KnowledgeIndexGenerationMapper generations;
    private final KnowledgeIndexDocumentMapper projections;
    private final KnowledgeSearchChunkMapper searchChunks;
    private final KnowledgeSearchIndexRepository searchIndex;
    private final KnowledgeChunker chunker;
    private final KnowledgeTextAnalyzer textAnalyzer;
    private final KnowledgeEmbeddingPort embedding;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;
    private final TransactionTemplate snapshotTransaction;
    private final TransactionTemplate activationTransaction;
    private final TransactionTemplate cleanupTransaction;

    /**
     * 创建分阶段 PostgreSQL 重建器。
     *
     * @param sourceDocuments 当前知识事实 Mapper
     * @param sourceTags 当前标签事实 Mapper
     * @param generations 投影 generation Mapper
     * @param projections 不可变文档投影 Mapper
     * @param searchChunks 检索分块校验 Mapper
     * @param searchIndex 搜索元数据与分块仓储
     * @param chunker 固定分块策略
     * @param textAnalyzer 索引与查询共用词项分析器
     * @param embedding 离线 Embedding 端口
     * @param objectMapper 标签 JSON 解析器
     * @param timeProvider UTC 时间端口
     * @param transactionManager PostgreSQL 事务管理器
     */
    public PostgresKnowledgeIndexRebuilder(
            KnowledgeDocumentMapper sourceDocuments,
            KnowledgeDocumentTagMapper sourceTags,
            KnowledgeIndexGenerationMapper generations,
            KnowledgeIndexDocumentMapper projections,
            KnowledgeSearchChunkMapper searchChunks,
            KnowledgeSearchIndexRepository searchIndex,
            KnowledgeChunker chunker,
            KnowledgeTextAnalyzer textAnalyzer,
            KnowledgeEmbeddingPort embedding,
            ObjectMapper objectMapper,
            TimeProvider timeProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.sourceDocuments = sourceDocuments;
        this.sourceTags = sourceTags;
        this.generations = generations;
        this.projections = projections;
        this.searchChunks = searchChunks;
        this.searchIndex = searchIndex;
        this.chunker = chunker;
        this.textAnalyzer = textAnalyzer;
        this.embedding = embedding;
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
        this.snapshotTransaction = transaction(transactionManager, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.activationTransaction = transaction(transactionManager, TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.cleanupTransaction = transaction(transactionManager, TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public KnowledgeIndexRebuildResult rebuild(UUID jobId, KnowledgeIndexRebuildProgress progress) {
        if (jobId == null || progress == null) {
            throw new IllegalArgumentException("knowledge rebuild context is required");
        }
        UUID generationId = UUID.randomUUID();
        long startedAt = System.nanoTime();
        LOGGER.info("knowledge_search_rebuild started jobId={} generationId={}", jobId, generationId);
        try {
            Snapshot snapshot = requireResult(snapshotTransaction.execute(
                    status -> freezeSnapshot(jobId, generationId)));
            progress.update(25);
            progress.heartbeat();

            // 模型初始化、分块和 Embedding 可能持续较久，必须在事实表快照事务提交后执行。
            KnowledgeEmbeddingModelDescriptor model = describeModel();
            List<PlannedChunk> planned = planChunks(generationId);
            searchIndex.createGeneration(new KnowledgeSearchGenerationMetadata(
                    generationId,
                    model.modelId(),
                    model.checksum(),
                    model.dimension(),
                    chunker.version(),
                    ReciprocalRankFusion.CONFIG_VERSION,
                    snapshot.documentCount(),
                    planned.size(),
                    snapshot.createdAt()
            ));
            progress.update(35);
            progress.heartbeat();

            writeEmbeddingBatches(generationId, planned, progress);
            validateCompleteGeneration(generationId, snapshot.documentCount(), planned.size());
            progress.update(90);
            progress.heartbeat();

            activateGeneration(generationId, snapshot.documentCount());
            progress.update(95);
            cleanupOldRetiredGenerations();
            LOGGER.info(
                    "knowledge_search_rebuild completed jobId={} generationId={} documentCount={} chunkCount={} "
                            + "modelId={} modelChecksum={} chunkVersion={} fusionVersion={} elapsedMs={}",
                    jobId, generationId, snapshot.documentCount(), planned.size(), model.modelId(),
                    checksumPrefix(model.checksum()), chunker.version(), ReciprocalRankFusion.CONFIG_VERSION,
                    elapsedMillis(startedAt));
            return new KnowledgeIndexRebuildResult(generationId, snapshot.documentCount());
        } catch (RuntimeException exception) {
            cleanupFailedGeneration(generationId);
            LOGGER.error(
                    "knowledge_search_rebuild failed jobId={} generationId={} errorCode={} errorType={} elapsedMs={}",
                    jobId, generationId, errorCode(exception), exception.getClass().getSimpleName(),
                    elapsedMillis(startedAt));
            throw exception;
        }
    }

    private Snapshot freezeSnapshot(UUID jobId, UUID generationId) {
        Instant createdAt = timeProvider.now();
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
                    .last("limit " + SNAPSHOT_BATCH_SIZE);
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
        }
        long stored = projectionCount(generationId);
        if (written != expected || stored != expected) {
            throw new IllegalStateException("knowledge projection count mismatch");
        }
        LOGGER.info("knowledge_search_snapshot completed jobId={} generationId={} documentCount={}",
                jobId, generationId, stored);
        return new Snapshot(createdAt, stored);
    }

    private KnowledgeEmbeddingModelDescriptor describeModel() {
        KnowledgeEmbeddingModelDescriptor model = embedding.describeModel();
        if (model == null || model.dimension() != VECTOR_DIMENSION
                || model.modelId() == null || model.modelId().isBlank()
                || model.checksum() == null || !model.checksum().matches("(?i)[0-9a-f]{64}")) {
            throw new KnowledgeEmbeddingUnavailableException(
                    new IllegalStateException("embedding model descriptor does not match index contract"));
        }
        return model;
    }

    private List<PlannedChunk> planChunks(UUID generationId) {
        List<PlannedChunk> planned = new ArrayList<>();
        UUID afterId = null;
        while (true) {
            var query = Wrappers.<KnowledgeIndexDocumentEntity>lambdaQuery()
                    .eq(KnowledgeIndexDocumentEntity::getGenerationId, generationId)
                    .orderByAsc(KnowledgeIndexDocumentEntity::getDocumentId)
                    .last("limit " + SNAPSHOT_BATCH_SIZE);
            if (afterId != null) {
                query.gt(KnowledgeIndexDocumentEntity::getDocumentId, afterId);
            }
            List<KnowledgeIndexDocumentEntity> batch = projections.selectList(query);
            if (batch.isEmpty()) {
                break;
            }
            for (KnowledgeIndexDocumentEntity projection : batch) {
                List<String> tags = normalizedTags(projection.getTags());
                List<KnowledgeChunk> chunks = chunker.chunk(projection.getBody());
                if (chunks.isEmpty()) {
                    throw new IllegalStateException("published projection produced no searchable chunk");
                }
                for (KnowledgeChunk chunk : chunks) {
                    AnalyzedKnowledgeText analyzed = textAnalyzer.analyzeDocument(
                            projection.getTitle(), tags, chunk.content());
                    planned.add(new PlannedChunk(projection, chunk, tags, analyzed));
                }
            }
            afterId = batch.getLast().getDocumentId();
        }
        return List.copyOf(planned);
    }

    private void writeEmbeddingBatches(
            UUID generationId,
            List<PlannedChunk> planned,
            KnowledgeIndexRebuildProgress progress
    ) {
        int written = 0;
        for (int start = 0; start < planned.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, planned.size());
            List<PlannedChunk> batch = planned.subList(start, end);
            List<KnowledgeEmbeddingInput> inputs = batch.stream().map(PlannedChunk::embeddingInput).toList();
            List<KnowledgeEmbeddingVector> vectors = embedding.embedDocuments(inputs);
            if (vectors == null || vectors.size() != batch.size()) {
                throw new KnowledgeEmbeddingUnavailableException(
                        new IllegalStateException("embedding batch size mismatch"));
            }
            List<KnowledgeSearchChunkWrite> writes = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                writes.add(batch.get(index).toWrite(vectors.get(index)));
            }
            searchIndex.writeChunks(writes);
            written += writes.size();
            int percentage = planned.isEmpty() ? 85 : 35 + Math.toIntExact((long) written * 50 / planned.size());
            progress.update(Math.min(85, percentage));
            progress.heartbeat();
            LOGGER.info("knowledge_search_rebuild_batch completed generationId={} batchChunkCount={} "
                            + "writtenChunkCount={} totalChunkCount={}",
                    generationId, writes.size(), written, planned.size());
        }
    }

    private void validateCompleteGeneration(UUID generationId, long expectedDocuments, long expectedChunks) {
        long storedProjections = projectionCount(generationId);
        long storedDocuments = searchChunks.countDocumentsByGeneration(generationId);
        long storedChunks = searchChunks.countByGeneration(generationId);
        long invalidDocuments = searchChunks.countInvalidDocuments(generationId);
        KnowledgeSearchGenerationMetadata metadata = searchIndex.findGeneration(generationId)
                .orElseThrow(() -> new IllegalStateException("knowledge search metadata is missing"));
        if (storedProjections != expectedDocuments
                || storedDocuments != expectedDocuments
                || storedChunks != expectedChunks
                || invalidDocuments != 0
                || metadata.documentCount() != expectedDocuments
                || metadata.chunkCount() != expectedChunks
                || metadata.vectorDimension() != VECTOR_DIMENSION) {
            throw new IllegalStateException("knowledge search generation validation failed");
        }
        LOGGER.info("knowledge_search_validation completed generationId={} documentCount={} chunkCount={} "
                        + "invalidDocumentCount={}",
                generationId, storedDocuments, storedChunks, invalidDocuments);
    }

    private void activateGeneration(UUID generationId, long documentCount) {
        requireResult(activationTransaction.execute(status -> {
            // 锁定当前 ACTIVE 后在同一短事务内退休与激活，任何失败都会恢复原可见 generation。
            generations.selectList(Wrappers.<KnowledgeIndexGenerationEntity>lambdaQuery()
                    .eq(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE")
                    .last("for update"));
            generations.update(null, Wrappers.<KnowledgeIndexGenerationEntity>lambdaUpdate()
                    .eq(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE")
                    .set(KnowledgeIndexGenerationEntity::getStatus, "RETIRED"));
            int activated = generations.update(null, Wrappers.<KnowledgeIndexGenerationEntity>lambdaUpdate()
                    .eq(KnowledgeIndexGenerationEntity::getId, generationId)
                    .eq(KnowledgeIndexGenerationEntity::getStatus, "BUILDING")
                    .set(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE")
                    .set(KnowledgeIndexGenerationEntity::getDocumentCount, documentCount)
                    .set(KnowledgeIndexGenerationEntity::getActivatedAt, timeProvider.now()));
            if (activated != 1) {
                throw new IllegalStateException("knowledge generation activation failed");
            }
            return Boolean.TRUE;
        }));
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
                .tags(serializeTags(tags))
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

    private String serializeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception exception) {
            // 标签属于冻结投影的一部分，序列化失败必须终止本次 generation，不能写入不完整快照。
            throw new IllegalStateException("knowledge projection tags serialization failed", exception);
        }
    }

    private List<String> normalizedTags(String json) {
        try {
            List<String> displayTags = objectMapper.readValue(json, new TypeReference<>() { });
            return displayTags.stream().map(DocumentTag::of).map(DocumentTag::normalizedName).toList();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("knowledge projection tags are invalid", exception);
        }
    }

    private long projectionCount(UUID generationId) {
        return projections.selectCount(Wrappers.<KnowledgeIndexDocumentEntity>lambdaQuery()
                .eq(KnowledgeIndexDocumentEntity::getGenerationId, generationId));
    }

    private void cleanupFailedGeneration(UUID generationId) {
        try {
            cleanupTransaction.executeWithoutResult(status -> generations.delete(
                    Wrappers.<KnowledgeIndexGenerationEntity>lambdaQuery()
                            .eq(KnowledgeIndexGenerationEntity::getId, generationId)
                            .eq(KnowledgeIndexGenerationEntity::getStatus, "BUILDING")));
            LOGGER.info("knowledge_search_failed_generation_cleanup completed generationId={}", generationId);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.warn("knowledge_search_failed_generation_cleanup failed generationId={} errorType={}",
                    generationId, cleanupFailure.getClass().getSimpleName());
        }
    }

    private void cleanupOldRetiredGenerations() {
        try {
            cleanupTransaction.executeWithoutResult(status -> {
                List<KnowledgeIndexGenerationEntity> retired = generations.selectList(
                        Wrappers.<KnowledgeIndexGenerationEntity>lambdaQuery()
                                .eq(KnowledgeIndexGenerationEntity::getStatus, "RETIRED")
                                .orderByDesc(KnowledgeIndexGenerationEntity::getActivatedAt)
                                .orderByDesc(KnowledgeIndexGenerationEntity::getCreatedAt));
                // 保留上一个成功 generation 作为诊断与回退证据；更旧数据删除失败不能影响 ACTIVE。
                retired.stream().skip(1).forEach(generation -> generations.deleteById(generation.getId()));
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("knowledge_index_retired_generation_cleanup_failed errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private TransactionTemplate transaction(PlatformTransactionManager manager, int isolationLevel) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(isolationLevel);
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(manager, definition);
    }

    private <T> T requireResult(T value) {
        if (value == null) {
            throw new IllegalStateException("knowledge rebuild transaction returned no result");
        }
        return value;
    }

    private String checksumPrefix(String checksum) {
        return checksum == null ? "invalid" : checksum.substring(0, Math.min(12, checksum.length()));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String errorCode(RuntimeException exception) {
        return exception instanceof ApplicationException applicationException
                ? applicationException.errorCode().name()
                : "UNEXPECTED_ERROR";
    }

    private record Snapshot(Instant createdAt, long documentCount) {
    }

    private record PlannedChunk(
            KnowledgeIndexDocumentEntity projection,
            KnowledgeChunk chunk,
            List<String> normalizedTags,
            AnalyzedKnowledgeText analyzed
    ) {
        private KnowledgeEmbeddingInput embeddingInput() {
            return new KnowledgeEmbeddingInput(
                    projection.getDocumentId(), chunk.chunkNo(), projection.getTitle(),
                    normalizedTags, chunk.content());
        }

        private KnowledgeSearchChunkWrite toWrite(KnowledgeEmbeddingVector vector) {
            return new KnowledgeSearchChunkWrite(
                    projection.getGenerationId(),
                    projection.getDocumentId(),
                    chunk.chunkNo(),
                    chunk.startOffset(),
                    chunk.endOffset(),
                    chunk.content(),
                    analyzed.titleTerms(),
                    analyzed.tagTerms(),
                    analyzed.contentTerms(),
                    vector.values(),
                    KnowledgeScopeType.valueOf(projection.getScopeType()),
                    projection.getProjectId(),
                    projection.getBranchId(),
                    DocumentFormat.valueOf(projection.getFormat()),
                    DocumentSourceType.valueOf(projection.getSourceType()),
                    normalizedTags,
                    projection.getSourceUpdatedAt()
            );
        }
    }
}
