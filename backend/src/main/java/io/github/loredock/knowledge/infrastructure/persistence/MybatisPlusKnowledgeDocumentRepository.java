package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.knowledge.application.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.application.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.application.KnowledgeBrowseContext;
import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.KnowledgeDocumentRepository;
import io.github.loredock.knowledge.application.PageResult;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentRevision;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.DocumentTag;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeDocument;
import io.github.loredock.knowledge.domain.KnowledgeDocumentFields;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import io.github.loredock.knowledge.domain.ReplacementLink;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MyBatis-Plus 知识文档仓储适配器。主体和标签写入共享事务，普通查询在 SQL 条件中同时限制状态与范围。
 */
@Repository
public class MybatisPlusKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentTagMapper tagMapper;

    /**
     * @param documentMapper 文档主体 Mapper
     * @param tagMapper 文档标签 Mapper
     */
    public MybatisPlusKnowledgeDocumentRepository(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeDocumentTagMapper tagMapper
    ) {
        this.documentMapper = documentMapper;
        this.tagMapper = tagMapper;
    }

    @Override
    @Transactional
    public void insert(KnowledgeDocument document) {
        documentMapper.insert(toEntity(document));
        insertTags(document);
    }

    @Override
    @Transactional
    public boolean update(KnowledgeDocument document, DocumentRevision expectedRevision) {
        if (document.revision().equals(expectedRevision)) {
            // 同值编辑由聚合保留同一 revision；这里先比对完整聚合，避免一次幂等 PUT 也改写 xmin 或标签。
            return findById(document.id()).filter(document::equals).isPresent();
        }
        KnowledgeDocumentEntity target = toEntity(document);
        LambdaUpdateWrapper<KnowledgeDocumentEntity> change = Wrappers.<KnowledgeDocumentEntity>lambdaUpdate()
                .set(KnowledgeDocumentEntity::getFormat, target.getFormat())
                .set(KnowledgeDocumentEntity::getTitle, target.getTitle())
                .set(KnowledgeDocumentEntity::getBody, target.getBody())
                .set(KnowledgeDocumentEntity::getDirectoryPath, target.getDirectoryPath())
                .set(KnowledgeDocumentEntity::getScopeType, target.getScopeType())
                .set(KnowledgeDocumentEntity::getProjectId, target.getProjectId())
                .set(KnowledgeDocumentEntity::getBranchId, target.getBranchId())
                .set(KnowledgeDocumentEntity::getSourceType, target.getSourceType())
                .set(KnowledgeDocumentEntity::getWikiUrl, target.getWikiUrl())
                .set(KnowledgeDocumentEntity::getOriginalFilename, target.getOriginalFilename())
                .set(KnowledgeDocumentEntity::getCurationNote, target.getCurationNote())
                .set(KnowledgeDocumentEntity::getStatus, target.getStatus())
                .set(KnowledgeDocumentEntity::getRevision, target.getRevision())
                .set(KnowledgeDocumentEntity::getReplacesDocumentId, target.getReplacesDocumentId())
                .set(KnowledgeDocumentEntity::getPublishedAt, target.getPublishedAt())
                .set(KnowledgeDocumentEntity::getPublishedBy, target.getPublishedBy())
                .set(KnowledgeDocumentEntity::getArchivedAt, target.getArchivedAt())
                .set(KnowledgeDocumentEntity::getArchivedBy, target.getArchivedBy())
                .set(KnowledgeDocumentEntity::getUpdatedAt, target.getUpdatedAt())
                .set(KnowledgeDocumentEntity::getUpdatedBy, target.getUpdatedBy())
                .eq(KnowledgeDocumentEntity::getId, document.id())
                .eq(KnowledgeDocumentEntity::getRevision, expectedRevision.value());
        int changed = documentMapper.update(null, change);
        if (changed == 0) {
            return false;
        }
        tagMapper.delete(Wrappers.<KnowledgeDocumentTagEntity>lambdaQuery()
                .eq(KnowledgeDocumentTagEntity::getDocumentId, document.id()));
        insertTags(document);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeDocument> findById(UUID documentId) {
        return Optional.ofNullable(documentMapper.selectById(documentId)).map(this::toDocument);
    }

    @Override
    public List<KnowledgeDocument> findAllByIdsForUpdate(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<UUID> stableIds = documentIds.stream().distinct().sorted().toList();
        List<UUID> lockedIds = documentMapper.selectIdsForUpdate(stableIds);
        // 锁仍由当前事务连接持有；完整实体改由 BaseMapper 显式映射加载，避免注解 SQL 复制结果映射。
        return documentMapper.selectByIds(lockedIds).stream()
                .sorted(java.util.Comparator.comparing(KnowledgeDocumentEntity::getId))
                .map(this::toDocument).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocument> findAdmin(AdminKnowledgeDocumentQuery query) {
        requirePage(query.page(), query.size());
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = Wrappers.lambdaQuery();
        if (query.scopeType() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getScopeType, query.scopeType().name());
        }
        if (query.projectId() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getProjectId, query.projectId());
        }
        if (query.branchId() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getBranchId, query.branchId());
        }
        if (query.directory() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getDirectoryPath, query.directory().value());
        }
        if (query.status() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getStatus, query.status().name());
        }
        if (query.tag() != null && !query.tag().isBlank()) {
            Set<UUID> taggedIds = new HashSet<>(tagMapper.selectList(
                            Wrappers.<KnowledgeDocumentTagEntity>lambdaQuery()
                                    .eq(KnowledgeDocumentTagEntity::getNormalizedName,
                                            DocumentTag.of(query.tag()).normalizedName()))
                    .stream().map(KnowledgeDocumentTagEntity::getDocumentId).toList());
            if (taggedIds.isEmpty()) {
                return emptyPage(query.page(), query.size());
            }
            wrapper.in(KnowledgeDocumentEntity::getId, taggedIds);
        }
        return selectPage(wrapper, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocument> findPublished(BrowseKnowledgeDocumentsQuery query) {
        requirePage(query.page(), query.size());
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = publishedWrapper(query.context());
        if (query.directory() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getDirectoryPath, query.directory().value());
        }
        return selectPage(wrapper, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findPublishedDirectoryPaths(KnowledgeBrowseContext context) {
        // 只选择目录列，但状态与范围条件与正文查询完全一致，避免目录计数泄露越界文档。
        return documentMapper.selectList(publishedWrapper(context)
                        .select(KnowledgeDocumentEntity::getDirectoryPath))
                .stream().map(KnowledgeDocumentEntity::getDirectoryPath).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeDocument> findPublishedById(UUID documentId, KnowledgeBrowseContext context) {
        return Optional.ofNullable(documentMapper.selectOne(
                publishedWrapper(context).eq(KnowledgeDocumentEntity::getId, documentId)))
                .map(this::toDocument);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findPublishedEligibleIds(Collection<UUID> documentIds, KnowledgeBrowseContext context) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        // 活动投影只是召回候选；这里再次应用实时状态与范围是授权边界，防止归档或范围变化等待重建时越权返回。
        return documentMapper.selectList(publishedWrapper(context)
                        .in(KnowledgeDocumentEntity::getId, documentIds)
                        .select(KnowledgeDocumentEntity::getId))
                .stream().map(KnowledgeDocumentEntity::getId).toList();
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> publishedWrapper(KnowledgeBrowseContext context) {
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getStatus, DocumentStatus.PUBLISHED.name());
        if (context.type() == KnowledgeBrowseContextType.GLOBAL) {
            return wrapper.eq(KnowledgeDocumentEntity::getScopeType, KnowledgeScopeType.GLOBAL.name());
        }
        if (context.projectId() == null || context.branchId() == null) {
            throw new IllegalArgumentException("project browse context is incomplete");
        }
        // 联合范围属于授权边界，必须直接进入 SQL，不能先跨项目加载后在 Java 中删除。
        return wrapper.and(scope -> scope
                .eq(KnowledgeDocumentEntity::getScopeType, KnowledgeScopeType.GLOBAL.name())
                .or(project -> project
                        .eq(KnowledgeDocumentEntity::getScopeType, KnowledgeScopeType.PROJECT.name())
                        .eq(KnowledgeDocumentEntity::getProjectId, context.projectId()))
                .or(branch -> branch
                        .eq(KnowledgeDocumentEntity::getScopeType, KnowledgeScopeType.BRANCH.name())
                        .eq(KnowledgeDocumentEntity::getProjectId, context.projectId())
                        .eq(KnowledgeDocumentEntity::getBranchId, context.branchId())));
    }

    private PageResult<KnowledgeDocument> selectPage(
            LambdaQueryWrapper<KnowledgeDocumentEntity> base,
            int page,
            int size
    ) {
        long total = documentMapper.selectCount(base.clone());
        if (total == 0) {
            return emptyPage(page, size);
        }
        int offset = Math.multiplyExact(page, size);
        base.orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
                .orderByAsc(KnowledgeDocumentEntity::getId)
                .last("limit " + size + " offset " + offset);
        List<KnowledgeDocument> items = documentMapper.selectList(base).stream().map(this::toDocument).toList();
        return new PageResult<>(items, page, size, total, totalPages(total, size));
    }

    private PageResult<KnowledgeDocument> emptyPage(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0, 0);
    }

    private int totalPages(long total, int size) {
        return Math.toIntExact((total + size - 1) / size);
    }

    private void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("knowledge page is invalid");
        }
    }

    private void insertTags(KnowledgeDocument document) {
        for (DocumentTag tag : document.fields().tags().values()) {
            tagMapper.insert(KnowledgeDocumentTagEntity.builder()
                    .documentId(document.id())
                    .normalizedName(tag.normalizedName())
                    .displayName(tag.displayName())
                    .build());
        }
    }

    private KnowledgeDocumentEntity toEntity(KnowledgeDocument document) {
        KnowledgeDocumentFields fields = document.fields();
        return KnowledgeDocumentEntity.builder()
                .id(document.id())
                .format(fields.format().name())
                .title(fields.title().value())
                .body(fields.body().value())
                .directoryPath(fields.directory().value())
                .scopeType(fields.scope().type().name())
                .projectId(fields.scope().projectId())
                .branchId(fields.scope().branchId())
                .sourceType(fields.source().type().name())
                .wikiUrl(fields.source().wikiUrl())
                .originalFilename(fields.source().originalFilename())
                .curationNote(fields.source().curationNote())
                .status(document.status().name())
                .revision(document.revision().value())
                .replacesDocumentId(document.replacement().replacesDocumentId())
                .publishedAt(document.publishedAt())
                .publishedBy(document.publishedBy())
                .archivedAt(document.archivedAt())
                .archivedBy(document.archivedBy())
                .createdAt(document.createdAt())
                .updatedAt(document.updatedAt())
                .createdBy(document.createdBy())
                .updatedBy(document.updatedBy())
                .build();
    }

    private KnowledgeDocument toDocument(KnowledgeDocumentEntity entity) {
        List<DocumentTag> tags = tagMapper.selectList(Wrappers.<KnowledgeDocumentTagEntity>lambdaQuery()
                        .eq(KnowledgeDocumentTagEntity::getDocumentId, entity.getId())
                        .orderByAsc(KnowledgeDocumentTagEntity::getNormalizedName))
                .stream().map(tag -> new DocumentTag(tag.getDisplayName(), tag.getNormalizedName())).toList();
        UUID replacedBy = Optional.ofNullable(documentMapper.selectOne(
                        Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                                .eq(KnowledgeDocumentEntity::getReplacesDocumentId, entity.getId())))
                .map(KnowledgeDocumentEntity::getId).orElse(null);
        return KnowledgeDocument.restore(
                entity.getId(),
                new KnowledgeDocumentFields(
                        DocumentFormat.valueOf(entity.getFormat()),
                        new DocumentTitle(entity.getTitle()),
                        new DocumentBody(entity.getBody()),
                        new DocumentDirectory(entity.getDirectoryPath()),
                        new DocumentTags(tags),
                        new DocumentSource(
                                DocumentSourceType.valueOf(entity.getSourceType()), entity.getWikiUrl(),
                                entity.getOriginalFilename(), entity.getCurationNote()),
                        new KnowledgeScope(
                                KnowledgeScopeType.valueOf(entity.getScopeType()), entity.getProjectId(), entity.getBranchId())
                ),
                DocumentStatus.valueOf(entity.getStatus()),
                new DocumentRevision(entity.getRevision()),
                new ReplacementLink(entity.getReplacesDocumentId(), replacedBy),
                entity.getPublishedAt(), entity.getPublishedBy(), entity.getArchivedAt(), entity.getArchivedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCreatedBy(), entity.getUpdatedBy()
        );
    }
}
