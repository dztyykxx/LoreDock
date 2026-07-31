package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.mapper.KnowledgeIndexGenerationMapper;
import io.github.loredock.knowledge.mapper.KnowledgeSearchChunkMapper;
import io.github.loredock.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeIndexRevisions;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从活动 generation 分块直接派生文档索引同步状态。 */
@Service
public class PublishedKnowledgeIndexDataService {

    private final KnowledgeIndexGenerationMapper generations;
    private final KnowledgeSearchChunkMapper chunks;

    /**
     * @param generations 唯一 generation Mapper
     * @param chunks 检索分块 Mapper
     */
    public PublishedKnowledgeIndexDataService(
            KnowledgeIndexGenerationMapper generations,
            KnowledgeSearchChunkMapper chunks
    ) {
        this.generations = generations;
        this.chunks = chunks;
    }

    /**
     * @param documentIds 当前页已发布文档标识
     * @return 活动 generation 是否存在及其对应文档修订
     */
    @Transactional(readOnly = true)
    public ActiveKnowledgeIndexRevisions readActiveRevisions(Collection<Long> documentIds) {
        KnowledgeIndexGenerationEntity active = generations.selectActive();
        if (active == null) {
            return new ActiveKnowledgeIndexRevisions(false, Map.of());
        }
        if (documentIds == null || documentIds.isEmpty()) {
            return new ActiveKnowledgeIndexRevisions(true, Map.of());
        }
        Map<Long, Long> revisions = new LinkedHashMap<>();
        chunks.selectActiveRevisions(documentIds)
                .forEach(chunk -> revisions.put(chunk.getDocumentId(), chunk.getSourceRevision()));
        return new ActiveKnowledgeIndexRevisions(true, revisions);
    }
}
