package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.application.ActiveKnowledgeIndexRevisions;
import io.github.loredock.knowledge.application.KnowledgeBrowseContext;
import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.KnowledgeIndexSyncStateReader;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexBatch;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexDocument;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexQuery;
import io.github.loredock.knowledge.application.PublishedKnowledgeIndexReader;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** MyBatis-Plus 活动 generation 读取适配器；范围条件直接进入 SQL，标签 JSON 只解析为文本值。 */
@Repository
public class MybatisPlusPublishedKnowledgeIndexReader
        implements PublishedKnowledgeIndexReader, KnowledgeIndexSyncStateReader {

    private final KnowledgeIndexGenerationMapper generations;
    private final KnowledgeIndexDocumentMapper documents;
    private final ObjectMapper objectMapper;

    /**
     * @param generations generation Mapper
     * @param documents 投影 Mapper
     * @param objectMapper JSON 标签解析器
     */
    public MybatisPlusPublishedKnowledgeIndexReader(
            KnowledgeIndexGenerationMapper generations,
            KnowledgeIndexDocumentMapper documents,
            ObjectMapper objectMapper
    ) {
        this.generations = generations;
        this.documents = documents;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PublishedKnowledgeIndexBatch read(PublishedKnowledgeIndexQuery query) {
        if (query == null || query.context() == null || query.size() < 1 || query.size() > 100) {
            throw new IllegalArgumentException("published knowledge index query is invalid");
        }
        Optional<UUID> active = activeGenerationId();
        if (active.isEmpty()) {
            return new PublishedKnowledgeIndexBatch(null, List.of(), null, false);
        }
        LambdaQueryWrapper<KnowledgeIndexDocumentEntity> wrapper = scoped(active.get(), query.context());
        if (query.afterDocumentId() != null) {
            wrapper.gt(KnowledgeIndexDocumentEntity::getDocumentId, query.afterDocumentId());
        }
        wrapper.orderByAsc(KnowledgeIndexDocumentEntity::getDocumentId).last("limit " + (query.size() + 1));
        List<KnowledgeIndexDocumentEntity> selected = documents.selectList(wrapper);
        boolean hasMore = selected.size() > query.size();
        List<KnowledgeIndexDocumentEntity> page = hasMore ? selected.subList(0, query.size()) : selected;
        List<PublishedKnowledgeIndexDocument> result = page.stream().map(this::toDocument).toList();
        UUID next = result.isEmpty() ? null : result.getLast().documentId();
        return new PublishedKnowledgeIndexBatch(active.get(), result, next, hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveKnowledgeIndexRevisions readActiveRevisions(Collection<UUID> documentIds) {
        Optional<UUID> active = activeGenerationId();
        if (active.isEmpty()) {
            return new ActiveKnowledgeIndexRevisions(false, Map.of());
        }
        if (documentIds == null || documentIds.isEmpty()) {
            return new ActiveKnowledgeIndexRevisions(true, Map.of());
        }
        List<KnowledgeIndexDocumentEntity> selected = documents.selectList(
                Wrappers.<KnowledgeIndexDocumentEntity>lambdaQuery()
                        .eq(KnowledgeIndexDocumentEntity::getGenerationId, active.get())
                        .in(KnowledgeIndexDocumentEntity::getDocumentId, documentIds)
                        .select(KnowledgeIndexDocumentEntity::getDocumentId,
                                KnowledgeIndexDocumentEntity::getSourceRevision));
        Map<UUID, Long> revisions = new LinkedHashMap<>();
        selected.forEach(entity -> revisions.put(entity.getDocumentId(), entity.getSourceRevision()));
        return new ActiveKnowledgeIndexRevisions(true, revisions);
    }

    private Optional<UUID> activeGenerationId() {
        return Optional.ofNullable(generations.selectOne(
                        Wrappers.<KnowledgeIndexGenerationEntity>lambdaQuery()
                                .eq(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE")
                                .select(KnowledgeIndexGenerationEntity::getId)))
                .map(KnowledgeIndexGenerationEntity::getId);
    }

    private LambdaQueryWrapper<KnowledgeIndexDocumentEntity> scoped(
            UUID generationId,
            KnowledgeBrowseContext context
    ) {
        LambdaQueryWrapper<KnowledgeIndexDocumentEntity> wrapper = Wrappers.<KnowledgeIndexDocumentEntity>lambdaQuery()
                .eq(KnowledgeIndexDocumentEntity::getGenerationId, generationId);
        if (context.type() == KnowledgeBrowseContextType.GLOBAL) {
            return wrapper.eq(KnowledgeIndexDocumentEntity::getScopeType, KnowledgeScopeType.GLOBAL.name());
        }
        if (context.projectId() == null || context.branchId() == null) {
            throw new IllegalArgumentException("project index context is incomplete");
        }
        return wrapper.and(scope -> scope
                .eq(KnowledgeIndexDocumentEntity::getScopeType, KnowledgeScopeType.GLOBAL.name())
                .or(project -> project
                        .eq(KnowledgeIndexDocumentEntity::getScopeType, KnowledgeScopeType.PROJECT.name())
                        .eq(KnowledgeIndexDocumentEntity::getProjectId, context.projectId()))
                .or(branch -> branch
                        .eq(KnowledgeIndexDocumentEntity::getScopeType, KnowledgeScopeType.BRANCH.name())
                        .eq(KnowledgeIndexDocumentEntity::getProjectId, context.projectId())
                        .eq(KnowledgeIndexDocumentEntity::getBranchId, context.branchId())));
    }

    private PublishedKnowledgeIndexDocument toDocument(KnowledgeIndexDocumentEntity entity) {
        return new PublishedKnowledgeIndexDocument(
                entity.getDocumentId(), entity.getSourceRevision(), DocumentFormat.valueOf(entity.getFormat()),
                entity.getTitle(), entity.getBody(), entity.getDirectoryPath(), tags(entity.getTags()),
                new DocumentSource(DocumentSourceType.valueOf(entity.getSourceType()),
                        entity.getWikiUrl(), entity.getOriginalFilename(), entity.getCurationNote()),
                scope(entity), entity.getSourceUpdatedAt());
    }

    private KnowledgeScope scope(KnowledgeIndexDocumentEntity entity) {
        return switch (KnowledgeScopeType.valueOf(entity.getScopeType())) {
            case GLOBAL -> KnowledgeScope.global();
            case PROJECT -> KnowledgeScope.project(entity.getProjectId());
            case BRANCH -> KnowledgeScope.branch(entity.getProjectId(), entity.getBranchId());
        };
    }

    private List<DocumentTag> tags(String json) {
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            return values.stream().map(DocumentTag::of).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("knowledge index tags are invalid", exception);
        }
    }
}
