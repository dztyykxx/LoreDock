package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.mapper.KnowledgeSearchChunkMapper;
import io.github.loredock.knowledge.mapper.KnowledgeIndexGenerationMapper;
import io.github.loredock.knowledge.model.command.KnowledgeSearchChunkWrite;
import io.github.loredock.knowledge.model.entity.KnowledgeSearchChunkEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.model.result.KnowledgeSearchGenerationMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 MyBatis-Plus 与参数化注解 SQL 保存搜索元数据和分块。
 *
 * <p>每个分块批次先完整校验，再在单个事务中写入，避免非法向量造成部分批次落库。</p>
 */
@Service
public class KnowledgeSearchIndexDataService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeSearchIndexDataService.class);
    private static final int VECTOR_DIMENSION = 512;

    private final KnowledgeIndexGenerationMapper generations;
    private final KnowledgeSearchChunkMapper chunks;

    /**
     * @param generations generation 元数据 Mapper
     * @param chunks 搜索分块 Mapper
     */
    public KnowledgeSearchIndexDataService(
            KnowledgeIndexGenerationMapper generations,
            KnowledgeSearchChunkMapper chunks
    ) {
        this.generations = generations;
        this.chunks = chunks;
    }

    public void updateChunkCount(Long generationId, long chunkCount) {
        KnowledgeIndexGenerationEntity generation = generations.selectById(generationId);
        if (generation == null) {
            throw new IllegalStateException("knowledge generation is missing");
        }
        generation.setChunkCount(chunkCount);
        generations.updateById(generation);
    }

    @Transactional
    public void writeChunks(List<KnowledgeSearchChunkWrite> chunks) {
        Objects.requireNonNull(chunks, "chunks are required");
        if (chunks.isEmpty()) {
            return;
        }

        Long generationId = chunks.getFirst().generationId();
        List<KnowledgeSearchChunkEntity> entities = new ArrayList<>(chunks.size());
        for (KnowledgeSearchChunkWrite chunk : chunks) {
            if (!generationId.equals(chunk.generationId())) {
                throw new IllegalArgumentException("a chunk batch must use one generation");
            }
            entities.add(toEntity(chunk));
        }

        LOG.info("knowledge_search_chunk_batch started generationId={} chunkCount={}",
                generationId, entities.size());
        try {
            entities.forEach(chunk -> this.chunks.insertSearchChunk(chunk));
            LOG.info("knowledge_search_chunk_batch completed generationId={} chunkCount={}",
                    generationId, entities.size());
        } catch (RuntimeException exception) {
            LOG.error("knowledge_search_chunk_batch failed generationId={} chunkCount={} errorType={}",
                    generationId, entities.size(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    public Optional<KnowledgeSearchGenerationMetadata> findGeneration(Long generationId) {
        return Optional.ofNullable(generations.selectById(generationId)).map(this::toMetadata);
    }

    @Transactional(readOnly = true)
    public Optional<ActiveKnowledgeSearchGeneration> findActive() {
        return Optional.ofNullable(generations.selectActive()).map(entity ->
                new ActiveKnowledgeSearchGeneration(
                        entity.getId(), entity.getModelId(), entity.getModelChecksum(),
                        entity.getVectorDimension(), entity.getChunkStrategyVersion(),
                        entity.getFusionConfigVersion(), entity.getDocumentCount(), entity.getChunkCount(),
                        entity.getCreatedAt()));
    }

    private KnowledgeSearchChunkEntity toEntity(KnowledgeSearchChunkWrite chunk) {
        float[] embedding = chunk.embedding();
        if (embedding.length != VECTOR_DIMENSION) {
            throw new IllegalArgumentException("knowledge embedding dimension must be 512");
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("knowledge embedding must contain only finite values");
            }
        }
        return KnowledgeSearchChunkEntity.builder()
                .generationId(chunk.generationId())
                .documentId(chunk.documentId())
                .chunkNo(chunk.chunkNo())
                .startOffset(chunk.startOffset())
                .endOffset(chunk.endOffset())
                .content(chunk.content())
                .sourceRevision(chunk.sourceRevision())
                .title(chunk.title())
                .tags(chunk.tagsJson())
                .wikiUrl(chunk.wikiUrl())
                .originalFilename(chunk.originalFilename())
                .curationNote(chunk.curationNote())
                .titleTerms(String.join(" ", chunk.titleTerms()))
                .tagTerms(String.join(" ", chunk.tagTerms()))
                .contentTerms(String.join(" ", chunk.contentTerms()))
                .embedding(vectorLiteral(embedding))
                .scopeType(chunk.scopeType().name())
                .projectId(chunk.projectId())
                .branchId(chunk.branchId())
                .format(chunk.format().name())
                .sourceType(chunk.sourceType().name())
                .normalizedTags(chunk.normalizedTags().toArray(String[]::new))
                .sourceUpdatedAt(chunk.sourceUpdatedAt())
                .build();
    }

    private String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder(embedding.length * 8).append('[');
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(embedding[index]));
        }
        return literal.append(']').toString();
    }

    private KnowledgeSearchGenerationMetadata toMetadata(KnowledgeIndexGenerationEntity entity) {
        return new KnowledgeSearchGenerationMetadata(
                entity.getId(),
                entity.getModelId(),
                entity.getModelChecksum(),
                entity.getVectorDimension(),
                entity.getChunkStrategyVersion(),
                entity.getFusionConfigVersion(),
                entity.getDocumentCount(),
                entity.getChunkCount(),
                entity.getCreatedAt()
        );
    }
}
