package io.github.loredock.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.loredock.knowledge.mapper.KnowledgeIndexGenerationMapper;
import io.github.loredock.knowledge.mapper.KnowledgeSearchChunkMapper;
import io.github.loredock.knowledge.model.DocumentTag;
import io.github.loredock.knowledge.model.command.KnowledgeSearchChunkWrite;
import io.github.loredock.knowledge.model.entity.KnowledgeDocumentEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.request.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.model.result.AnalyzedKnowledgeText;
import io.github.loredock.knowledge.model.result.KnowledgeChunk;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.model.result.KnowledgeIndexRebuildResult;
import io.github.loredock.knowledge.model.result.KnowledgeSearchGenerationMetadata;
import io.github.loredock.knowledge.service.search.CjkKnowledgeTextAnalyzer;
import io.github.loredock.knowledge.service.search.KnowledgeEmbeddingService;
import io.github.loredock.knowledge.service.search.ReciprocalRankFusion;
import io.github.loredock.knowledge.service.search.indexing.DeterministicKnowledgeChunker;
import io.github.loredock.platform.web.ApplicationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 知识搜索索引重建器，提供全量重建与增量刷新两条路径。
 *
 * <p>全量重建（{@link #rebuild(Long, Progress)}）在短 {@code REPEATABLE READ} 事务中冻结
 * PUBLISHED 文档快照；分块、分析和 Embedding 全部在事务外执行并分批短事务写入。只有计数、
 * 序号、关键词与向量完整性通过后，才在另一个短事务中退休旧 ACTIVE 并激活新 generation。</p>
 *
 * <p>增量刷新（{@link #refresh(Long, Progress)}）不创建新代次，只对修订变化的文档重新分块、
 * Embedding 并逐文档原子替换分块，供发布自动触发；模型指纹不一致或无 ACTIVE 时自动降级到
 * 全量重建。</p>
 */
@Service
public class KnowledgeIndexRebuildService {

    /** 重建过程向后台任务上报进度和心跳的简单回调数据，不建立独立业务接口。 */
    public record Progress(java.util.function.IntConsumer progressUpdate, Runnable heartbeatAction) {
        public Progress {
            java.util.Objects.requireNonNull(progressUpdate, "progressUpdate is required");
            java.util.Objects.requireNonNull(heartbeatAction, "heartbeatAction is required");
        }

        public void update(int percentage) {
            progressUpdate.accept(percentage);
        }

        public void heartbeat() {
            heartbeatAction.run();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexRebuildService.class);
    private static final int SNAPSHOT_BATCH_SIZE = 100;
    private static final int EMBEDDING_BATCH_SIZE = 16;
    private static final int VECTOR_DIMENSION = 512;

    private final KnowledgeDocumentMapper sourceDocuments;
    private final KnowledgeIndexGenerationMapper generations;
    private final KnowledgeSearchChunkMapper searchChunks;
    private final KnowledgeSearchIndexDataService searchIndex;
    private final DeterministicKnowledgeChunker chunker;
    private final CjkKnowledgeTextAnalyzer textAnalyzer;
    private final KnowledgeEmbeddingService embedding;
    private final ObjectMapper objectMapper;
    private final Clock timeProvider;
    private final TransactionTemplate snapshotTransaction;
    private final TransactionTemplate activationTransaction;
    private final TransactionTemplate cleanupTransaction;

    /**
     * 创建分阶段 PostgreSQL 重建器。
     *
     * @param sourceDocuments 当前知识事实 Mapper
     * @param generations 投影 generation Mapper
     * @param searchChunks 检索分块校验 Mapper
     * @param searchIndex 搜索元数据与分块仓储
     * @param chunker 固定分块策略
     * @param textAnalyzer 索引与查询共用词项分析器
     * @param embedding 离线 Embedding 端口
     * @param objectMapper 标签 JSON 解析器
     * @param timeProvider UTC 时间端口
     * @param transactionManager PostgreSQL 事务管理器
     */
    public KnowledgeIndexRebuildService(
            KnowledgeDocumentMapper sourceDocuments,
            KnowledgeIndexGenerationMapper generations,
            KnowledgeSearchChunkMapper searchChunks,
            KnowledgeSearchIndexDataService searchIndex,
            DeterministicKnowledgeChunker chunker,
            CjkKnowledgeTextAnalyzer textAnalyzer,
            KnowledgeEmbeddingService embedding,
            ObjectMapper objectMapper,
            Clock timeProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.sourceDocuments = sourceDocuments;
        this.generations = generations;
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

    public KnowledgeIndexRebuildResult rebuild(Long jobId, KnowledgeIndexRebuildService.Progress progress) {
        if (jobId == null || progress == null) {
            throw new IllegalArgumentException("knowledge rebuild context is required");
        }
        Long generationId = null;
        long startedAt = System.nanoTime();
        LOGGER.info("knowledge_search_rebuild started jobId={}", jobId);
        try {
            KnowledgeEmbeddingModelDescriptor model = describeModel();
            Snapshot snapshot = requireResult(snapshotTransaction.execute(
                    status -> freezeSnapshot(jobId, model)));
            generationId = snapshot.generationId();
            progress.update(25);
            progress.heartbeat();

            // 模型初始化、分块和 Embedding 可能持续较久，必须在事实表快照事务提交后执行。
            List<PlannedChunk> planned = planChunks(generationId, snapshot.documents());
            searchIndex.updateChunkCount(generationId, planned.size());
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

    /**
     * 增量刷新发布变更文档的检索分块，不创建新 generation。
     *
     * <p>以 ACTIVE generation 的分块修订与事实表当前修订做 diff：对修订变化的文档重新切块、
     * Embedding 并逐文档原子替换分块，对失去发布资格的文档删除分块，最后重算代次计数。
     * 无 ACTIVE generation（首次部署）或当前模型指纹与 ACTIVE 不一致（向量空间不兼容）时
     * 降级为全量重建，保证索引最终可用。</p>
     *
     * @param jobId 当前后台任务标识
     * @param progress 重建过程进度与心跳回调
     * @return 刷新或降级重建后生效的 generation 与文档数
     */
    public KnowledgeIndexRebuildResult refresh(Long jobId, KnowledgeIndexRebuildService.Progress progress) {
        if (jobId == null || progress == null) {
            throw new IllegalArgumentException("knowledge refresh context is required");
        }
        long startedAt = System.nanoTime();
        LOGGER.info("knowledge_search_refresh started jobId={}", jobId);
        try {
            KnowledgeEmbeddingModelDescriptor model = describeModel();
            progress.update(10);
            progress.heartbeat();

            ActiveKnowledgeSearchGeneration active = searchIndex.findActive().orElse(null);
            if (active == null) {
                LOGGER.info("knowledge_search_refresh fallback jobId={} reason=no_active_generation", jobId);
                return rebuild(jobId, progress);
            }
            if (!modelFingerprintMatches(model, active)) {
                LOGGER.info("knowledge_search_refresh fallback jobId={} reason=model_fingerprint_mismatch", jobId);
                return rebuild(jobId, progress);
            }
            progress.update(20);
            progress.heartbeat();

            Long generationId = active.generationId();
            List<Long> toRefresh = searchChunks.selectDocumentIdsNeedingRefresh(generationId);
            List<Long> toRemove = searchChunks.selectDocumentIdsToRemove(generationId);
            LOGGER.info("knowledge_search_refresh_diff completed jobId={} generationId={} refreshCount={} "
                            + "removeCount={}",
                    jobId, generationId, toRefresh.size(), toRemove.size());
            progress.update(25);
            progress.heartbeat();

            // 先移除失去资格文档的分块，再逐文档原子替换变更内容，两个集合天然不相交。
            if (!toRemove.isEmpty()) {
                searchIndex.deleteDocumentChunks(generationId, toRemove);
            }
            if (!toRefresh.isEmpty()) {
                refreshDocuments(generationId, toRefresh, progress);
                validateRefreshedDocuments(generationId, toRefresh);
            }
            long documentCount = searchIndex.updateCounts(generationId);
            progress.update(95);
            progress.heartbeat();

            LOGGER.info("knowledge_search_refresh completed jobId={} generationId={} refreshCount={} "
                            + "removeCount={} documentCount={} modelId={} modelChecksum={} chunkVersion={} "
                            + "fusionVersion={} elapsedMs={}",
                    jobId, generationId, toRefresh.size(), toRemove.size(), documentCount, model.modelId(),
                    checksumPrefix(model.checksum()), chunker.version(), ReciprocalRankFusion.CONFIG_VERSION,
                    elapsedMillis(startedAt));
            return new KnowledgeIndexRebuildResult(generationId, documentCount);
        } catch (RuntimeException exception) {
            LOGGER.error("knowledge_search_refresh failed jobId={} errorCode={} errorType={} elapsedMs={}",
                    jobId, errorCode(exception), exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            throw exception;
        }
    }

    /** @return 当前模型与 ACTIVE generation 的指纹是否一致；不一致时旧向量无法与新模型混合使用 */
    private boolean modelFingerprintMatches(
            KnowledgeEmbeddingModelDescriptor model,
            ActiveKnowledgeSearchGeneration active
    ) {
        return active.modelId().equals(model.modelId())
                && active.modelChecksum().equalsIgnoreCase(model.checksum())
                && active.vectorDimension() == model.dimension();
    }

    /** 分批加载需要刷新的文档，逐文档切块、Embedding 后原子替换其在 ACTIVE generation 中的分块。 */
    private void refreshDocuments(
            Long generationId,
            List<Long> documentIds,
            KnowledgeIndexRebuildService.Progress progress
    ) {
        int processed = 0;
        for (int start = 0; start < documentIds.size(); start += SNAPSHOT_BATCH_SIZE) {
            List<Long> batchIds = documentIds.subList(
                    start, Math.min(start + SNAPSHOT_BATCH_SIZE, documentIds.size()));
            List<KnowledgeDocumentEntity> batch = sourceDocuments.selectBatchIds(batchIds);
            if (batch.size() != batchIds.size()) {
                throw new IllegalStateException("knowledge refresh document batch is incomplete");
            }
            for (KnowledgeDocumentEntity document : batch) {
                // 文档名用于测试时实时确认当前正在重算哪篇文档，不包含正文内容。
                LOGGER.info("knowledge_search_refresh_document started generationId={} documentId={} "
                                + "documentTitle={} sourceRevision={}",
                        generationId, document.getId(), document.getTitle(), document.getRevision());
                replaceDocumentChunks(generationId, document);
                processed++;
                int percentage = 25 + Math.toIntExact((long) processed * 55 / documentIds.size());
                progress.update(Math.min(85, percentage));
                progress.heartbeat();
            }
        }
    }

    /** 对单个文档切块、Embedding 并原子替换其分块；失败时旧修订分块保留，不产生半截文档。 */
    private void replaceDocumentChunks(Long generationId, KnowledgeDocumentEntity document) {
        SourceDocument source = snapshotDocument(document);
        List<PlannedChunk> planned = planChunks(generationId, List.of(source));
        List<KnowledgeSearchChunkWrite> writes = new ArrayList<>(planned.size());
        for (int start = 0; start < planned.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, planned.size());
            List<PlannedChunk> batch = planned.subList(start, end);
            List<KnowledgeEmbeddingVector> vectors = embedding.embedDocuments(
                    batch.stream().map(PlannedChunk::embeddingInput).toList());
            if (vectors == null || vectors.size() != batch.size()) {
                throw new KnowledgeEmbeddingUnavailableException(
                        new IllegalStateException("embedding batch size mismatch"));
            }
            for (int index = 0; index < batch.size(); index++) {
                writes.add(batch.get(index).toWrite(vectors.get(index)));
            }
        }
        searchIndex.replaceDocumentChunks(generationId, source.documentId(), writes);
        // 标题用于测试时确认本次刷新实际重算了哪些文档，不包含正文内容。
        LOGGER.info("knowledge_search_refresh_document completed generationId={} documentId={} "
                        + "documentTitle={} sourceRevision={} chunkCount={}",
                generationId, source.documentId(), source.title(), source.sourceRevision(), writes.size());
    }

    /** 校验本次刷新涉及的文档分块完整性，任一文档非法即终止本次刷新。 */
    private void validateRefreshedDocuments(Long generationId, List<Long> documentIds) {
        long invalidDocuments = searchChunks.countInvalidDocumentsByGenerationAndDocuments(generationId, documentIds);
        if (invalidDocuments != 0) {
            throw new IllegalStateException("knowledge search refresh validation failed");
        }
        LOGGER.info("knowledge_search_refresh_validation completed generationId={} documentCount={} "
                        + "invalidDocumentCount={}",
                generationId, documentIds.size(), invalidDocuments);
    }

    private Snapshot freezeSnapshot(Long jobId, KnowledgeEmbeddingModelDescriptor model) {
        Instant createdAt = timeProvider.instant();
        long expected = sourceDocuments.selectCount(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getStatus, DocumentStatus.PUBLISHED.name()));
        List<SourceDocument> documents = new ArrayList<>();
        Long afterId = null;
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
            batch.stream().map(this::snapshotDocument).forEach(documents::add);
            afterId = batch.getLast().getId();
        }
        if (documents.size() != expected) {
            throw new IllegalStateException("knowledge snapshot count mismatch");
        }
        KnowledgeIndexGenerationEntity generation = KnowledgeIndexGenerationEntity.builder()
                .jobId(jobId).status("BUILDING")
                .modelId(model.modelId()).modelChecksum(model.checksum()).vectorDimension(model.dimension())
                .chunkStrategyVersion(chunker.version()).fusionConfigVersion(ReciprocalRankFusion.CONFIG_VERSION)
                .documentCount(expected).chunkCount(0L)
                .createdAt(createdAt).activatedAt(null).build();
        generations.insert(generation);
        Long generationId = java.util.Objects.requireNonNull(
                generation.getId(), "知识索引代次写入后数据库未回填主键");
        LOGGER.info("knowledge_search_snapshot completed jobId={} generationId={} documentCount={}",
                jobId, generationId, documents.size());
        return new Snapshot(generationId, createdAt, List.copyOf(documents));
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

    private List<PlannedChunk> planChunks(Long generationId, List<SourceDocument> documents) {
        List<PlannedChunk> planned = new ArrayList<>();
        for (SourceDocument document : documents) {
            List<KnowledgeChunk> chunks = chunker.chunk(document.body());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("published document produced no searchable chunk");
            }
            for (KnowledgeChunk chunk : chunks) {
                AnalyzedKnowledgeText analyzed = textAnalyzer.analyzeDocument(
                        document.title(), document.normalizedTags(), chunk.content());
                planned.add(new PlannedChunk(generationId, document, chunk, analyzed));
            }
        }
        return List.copyOf(planned);
    }

    private void writeEmbeddingBatches(
            Long generationId,
            List<PlannedChunk> planned,
            KnowledgeIndexRebuildService.Progress progress
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

    private void validateCompleteGeneration(Long generationId, long expectedDocuments, long expectedChunks) {
        long storedDocuments = searchChunks.countDocumentsByGeneration(generationId);
        long storedChunks = searchChunks.countByGeneration(generationId);
        long invalidDocuments = searchChunks.countInvalidDocuments(generationId);
        KnowledgeSearchGenerationMetadata metadata = searchIndex.findGeneration(generationId)
                .orElseThrow(() -> new IllegalStateException("knowledge search metadata is missing"));
        if (storedDocuments != expectedDocuments
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

    private void activateGeneration(Long generationId, long documentCount) {
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
                    .set(KnowledgeIndexGenerationEntity::getActivatedAt, timeProvider.instant()));
            if (activated != 1) {
                throw new IllegalStateException("knowledge generation activation failed");
            }
            return Boolean.TRUE;
        }));
    }

    private SourceDocument snapshotDocument(KnowledgeDocumentEntity document) {
        List<DocumentTag> tags = documentTags(document.getTags());
        List<String> displayTags = tags.stream().map(DocumentTag::displayName).toList();
        List<String> normalizedTags = tags.stream().map(DocumentTag::normalizedName).toList();
        return new SourceDocument(
                document.getId(), document.getRevision(), document.getFormat(), document.getTitle(), document.getBody(),
                serializeTags(displayTags), normalizedTags, document.getScopeType(), document.getProjectId(),
                document.getBranchId(), document.getSourceType(), document.getWikiUrl(),
                document.getOriginalFilename(), document.getCurationNote(), document.getUpdatedAt());
    }

    private String serializeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception exception) {
            // 标签属于冻结投影的一部分，序列化失败必须终止本次 generation，不能写入不完整快照。
            throw new IllegalStateException("knowledge projection tags serialization failed", exception);
        }
    }

    private List<DocumentTag> documentTags(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<DocumentTag>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("knowledge document tags cannot be parsed", exception);
        }
    }

    private void cleanupFailedGeneration(Long generationId) {
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
                // 保留上一个成功 generation 作为诊断与回退证据；历史运行引用的更旧索引继续保留。
                retired.stream().skip(1)
                        .forEach(generation -> generations.deleteUnreferencedRetiredById(generation.getId()));
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

    private record Snapshot(Long generationId, Instant createdAt, List<SourceDocument> documents) {
        private long documentCount() {
            return documents.size();
        }
    }

    private record SourceDocument(
            Long documentId, long sourceRevision, String format, String title, String body,
            String tagsJson, List<String> normalizedTags, String scopeType, Long projectId, Long branchId,
            String sourceType, String wikiUrl, String originalFilename, String curationNote, Instant sourceUpdatedAt
    ) {
        private SourceDocument {
            normalizedTags = List.copyOf(normalizedTags);
        }
    }

    private record PlannedChunk(
            Long generationId,
            SourceDocument document,
            KnowledgeChunk chunk,
            AnalyzedKnowledgeText analyzed
    ) {
        private KnowledgeEmbeddingInput embeddingInput() {
            return new KnowledgeEmbeddingInput(
                    document.documentId(), chunk.chunkNo(), document.title(),
                    document.normalizedTags(), chunk.content());
        }

        private KnowledgeSearchChunkWrite toWrite(KnowledgeEmbeddingVector vector) {
            return new KnowledgeSearchChunkWrite(
                    generationId,
                    document.documentId(),
                    chunk.chunkNo(),
                    chunk.startOffset(),
                    chunk.endOffset(),
                    chunk.content(),
                    document.sourceRevision(),
                    document.title(),
                    document.tagsJson(),
                    document.wikiUrl(),
                    document.originalFilename(),
                    document.curationNote(),
                    analyzed.titleTerms(),
                    analyzed.tagTerms(),
                    analyzed.contentTerms(),
                    vector.values(),
                    KnowledgeScopeType.valueOf(document.scopeType()),
                    document.projectId(),
                    document.branchId(),
                    DocumentFormat.valueOf(document.format()),
                    DocumentSourceType.valueOf(document.sourceType()),
                    document.normalizedTags(),
                    document.sourceUpdatedAt()
            );
        }
    }
}
