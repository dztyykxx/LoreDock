package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.KnowledgeDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 普通与管理员知识查询服务；所有可见性均委托仓储在 SQL 阶段限制，服务只映射目录和应用 DTO。
 */
@Service
public class KnowledgeDocumentQueryService
        implements KnowledgeDocumentQueryUseCase, AdminKnowledgeDocumentQueryUseCase {

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentViewFactory views;

    /**
     * @param documents 带查询前置隔离的文档仓储
     * @param views 应用视图工厂
     */
    public KnowledgeDocumentQueryService(
            KnowledgeDocumentRepository documents,
            KnowledgeDocumentViewFactory views
    ) {
        this.documents = documents;
        this.views = views;
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBrowseResult browse(BrowseKnowledgeDocumentsQuery query) {
        PageResult<KnowledgeDocument> page = documents.findPublished(query);
        List<KnowledgeDirectoryNode> directories = directoryNodes(
                documents.findPublishedDirectoryPaths(query.context()),
                query.directory() == null ? "" : query.directory().value());
        return new KnowledgeBrowseResult(directories, mapPage(page));
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeDocumentView get(ReadKnowledgeDocumentQuery query) {
        KnowledgeDocument document = documents.findPublishedById(query.documentId(), query.context())
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
        return views.create(document);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocumentSummary> list(AdminKnowledgeDocumentQuery query) {
        return mapPage(documents.findAdmin(query));
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeDocumentView get(UUID documentId) {
        return documents.findById(documentId)
                .map(views::create)
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
    }

    private PageResult<KnowledgeDocumentSummary> mapPage(PageResult<KnowledgeDocument> page) {
        return new PageResult<>(
                views.summaries(page.items()),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    private List<KnowledgeDirectoryNode> directoryNodes(List<String> paths, String parent) {
        String prefix = parent.isEmpty() ? "" : parent + "/";
        Map<String, Long> counts = new LinkedHashMap<>();
        paths.stream().sorted().forEach(path -> {
            if (!path.startsWith(prefix) || path.equals(parent)) {
                return;
            }
            String remainder = path.substring(prefix.length());
            if (remainder.isEmpty()) {
                return;
            }
            String name = remainder.substring(0, remainder.indexOf('/') < 0 ? remainder.length() : remainder.indexOf('/'));
            String childPath = prefix + name;
            counts.merge(childPath, 1L, Long::sum);
        });
        return counts.entrySet().stream()
                .map(entry -> new KnowledgeDirectoryNode(
                        entry.getKey(), entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1), entry.getValue()))
                .toList();
    }
}
