package io.github.loredock.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentRevision;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTag;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.ReplacementLink;
import io.github.loredock.knowledge.model.entity.KnowledgeDocumentEntity;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.request.AdminBrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.request.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.result.PageResult;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis-Plus 知识文档仓储适配器。主体和标签写入共享事务，普通查询在 SQL 条件中同时限制状态与范围。
 */
@Service
public class KnowledgeDocumentDataService {

    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param documentMapper 文档主体 Mapper
     * @param objectMapper 标签字段 JSON 编解码器
     */
    public KnowledgeDocumentDataService(
            KnowledgeDocumentMapper documentMapper,
            ObjectMapper objectMapper
    ) {
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void insert(KnowledgeDocument document) {
        KnowledgeDocumentEntity entity = toEntity(document);
        documentMapper.insert(entity);
    }

    /**
     * 写入由数据库分配主键的新草稿，并在同一事务中保存标签。
     *
     * @param fields 已校验的文档字段
     * @param audit 创建审计
     * @return 已带数据库主键的文档聚合
     */
    @Transactional
    public KnowledgeDocument insertDraft(KnowledgeDocumentFields fields, DocumentAudit audit) {
        KnowledgeDocumentEntity entity = KnowledgeDocumentEntity.builder()
                .format(fields.format().name())
                .title(fields.title().value())
                .body(fields.body().value())
                .directoryPath(fields.directory().value())
                .tags(serializeTags(fields.tags()))
                .scopeType(fields.scope().type().name())
                .projectId(fields.scope().projectId())
                .branchId(fields.scope().branchId())
                .sourceType(fields.source().type().name())
                .wikiUrl(fields.source().wikiUrl())
                .originalFilename(fields.source().originalFilename())
                .curationNote(fields.source().curationNote())
                .status(DocumentStatus.DRAFT.name())
                .revision(1L)
                .createdAt(audit.at())
                .updatedAt(audit.at())
                .createdBy(audit.actor())
                .updatedBy(audit.actor())
                .build();
        documentMapper.insert(entity);
        Long documentId = Objects.requireNonNull(entity.getId(), "知识文档写入后数据库未回填主键");
        return KnowledgeDocument.create(documentId, fields, audit);
    }

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
                .set(KnowledgeDocumentEntity::getTags, target.getTags())
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
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<KnowledgeDocument> findById(Long documentId) {
        return Optional.ofNullable(documentMapper.selectById(documentId)).map(this::toDocument);
    }

    public List<KnowledgeDocument> findAllByIdsForUpdate(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<Long> stableIds = documentIds.stream().distinct().sorted().toList();
        List<Long> lockedIds = documentMapper.selectIdsForUpdate(stableIds);
        // 锁仍由当前事务连接持有；完整实体改由 BaseMapper 显式映射加载，避免注解 SQL 复制结果映射。
        return documentMapper.selectByIds(lockedIds).stream()
                .sorted(java.util.Comparator.comparing(KnowledgeDocumentEntity::getId))
                .map(this::toDocument).toList();
    }

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
            DocumentTag tag = DocumentTag.of(query.tag());
            Set<Long> taggedIds = new HashSet<>(documentMapper.selectIdsByTag(tag.normalizedName()));
            if (taggedIds.isEmpty()) {
                return emptyPage(query.page(), query.size());
            }
            wrapper.in(KnowledgeDocumentEntity::getId, taggedIds);
        }
        return selectPage(wrapper, query.page(), query.size());
    }

    /**
     * 管理员按通用或项目联合上下文读取文档，可按生命周期过滤，目录使用自身及后代子树语义。
     *
     * @param query 已解析上下文与分页
     * @return 当前目录子树摘要页
     */
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocument> findAdmin(AdminBrowseKnowledgeDocumentsQuery query) {
        requirePage(query.page(), query.size());
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = contextWrapper(query.context());
        if (query.status() != null) {
            wrapper.eq(KnowledgeDocumentEntity::getStatus, query.status().name());
        }
        applyDirectoryFilter(wrapper, query.directory(), true);
        return selectPage(wrapper, query.page(), query.size());
    }

    /**
     * @param context 管理员当前通用或项目上下文
     * @param status 可选生命周期状态
     * @return 同一状态过滤范围内的目录路径，供完整树递归计数
     */
    @Transactional(readOnly = true)
    public List<String> findAdminDirectoryPaths(KnowledgeBrowseContext context, DocumentStatus status) {
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = contextWrapper(context);
        if (status != null) {
            wrapper.eq(KnowledgeDocumentEntity::getStatus, status.name());
        }
        return documentMapper.selectList(wrapper
                        .select(KnowledgeDocumentEntity::getDirectoryPath))
                .stream().map(KnowledgeDocumentEntity::getDirectoryPath).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocument> findPublished(BrowseKnowledgeDocumentsQuery query) {
        requirePage(query.page(), query.size());
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = publishedWrapper(query.context());
        applyDirectoryFilter(wrapper, query.directory(), query.includeDescendants());
        return selectPage(wrapper, query.page(), query.size());
    }

    @Transactional(readOnly = true)
    public List<String> findPublishedDirectoryPaths(KnowledgeBrowseContext context) {
        // 只选择目录列，但状态与范围条件与正文查询完全一致，避免目录计数泄露越界文档。
        return documentMapper.selectList(publishedWrapper(context)
                        .select(KnowledgeDocumentEntity::getDirectoryPath))
                .stream().map(KnowledgeDocumentEntity::getDirectoryPath).toList();
    }

    @Transactional(readOnly = true)
    public Optional<KnowledgeDocument> findPublishedById(Long documentId, KnowledgeBrowseContext context) {
        return Optional.ofNullable(documentMapper.selectOne(
                publishedWrapper(context).eq(KnowledgeDocumentEntity::getId, documentId)))
                .map(this::toDocument);
    }

    @Transactional(readOnly = true)
    public List<Long> findPublishedEligibleIds(Collection<Long> documentIds, KnowledgeBrowseContext context) {
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
        return applyContext(wrapper, context);
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> contextWrapper(KnowledgeBrowseContext context) {
        return applyContext(Wrappers.lambdaQuery(), context);
    }

    private LambdaQueryWrapper<KnowledgeDocumentEntity> applyContext(
            LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper,
            KnowledgeBrowseContext context
    ) {
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

    private void applyDirectoryFilter(
            LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper,
            DocumentDirectory directory,
            boolean includeDescendants
    ) {
        if (directory == null) {
            return;
        }
        String path = directory.value();
        if (!includeDescendants) {
            wrapper.eq(KnowledgeDocumentEntity::getDirectoryPath, path);
            return;
        }
        if (path.isEmpty()) {
            return;
        }
        String descendantPattern = escapeLikeLiteral(path + "/") + "%";
        // 目录允许百分号和下划线；显式 ESCAPE 保证它们只按业务路径字面值匹配。
        wrapper.and(filter -> filter
                .eq(KnowledgeDocumentEntity::getDirectoryPath, path)
                .or().apply("directory_path LIKE {0} ESCAPE '!'", descendantPattern));
    }

    private String escapeLikeLiteral(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
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

    private KnowledgeDocumentEntity toEntity(KnowledgeDocument document) {
        KnowledgeDocumentFields fields = document.fields();
        return KnowledgeDocumentEntity.builder()
                .id(document.id())
                .format(fields.format().name())
                .title(fields.title().value())
                .body(fields.body().value())
                .directoryPath(fields.directory().value())
                .tags(serializeTags(fields.tags()))
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
        List<DocumentTag> tags = deserializeTags(entity.getTags());
        Long replacedBy = Optional.ofNullable(documentMapper.selectOne(
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

    private String serializeTags(DocumentTags tags) {
        try {
            return objectMapper.writeValueAsString(tags.values());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识文档标签序列化失败", exception);
        }
    }

    private List<DocumentTag> deserializeTags(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<DocumentTag>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("知识文档标签读取失败", exception);
        }
    }
}
