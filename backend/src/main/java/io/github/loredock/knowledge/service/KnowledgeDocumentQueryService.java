package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.converter.KnowledgeDocumentViewFactory;
import io.github.loredock.knowledge.exception.KnowledgeDocumentNotFoundException;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.request.AdminBrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.request.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.ReadKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.result.KnowledgeBrowseResult;
import io.github.loredock.knowledge.model.result.KnowledgeDirectoryNode;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentSummary;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.model.result.PageResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 普通与管理员知识查询服务；所有可见性均委托仓储在 SQL 阶段限制，服务只映射目录和应用 DTO。
 */
@Service
public class KnowledgeDocumentQueryService
 {

    private final KnowledgeDocumentDataService documents;
    private final KnowledgeDocumentViewFactory views;

    /**
     * @param documents 带查询前置隔离的文档仓储
     * @param views 应用视图工厂
     */
    public KnowledgeDocumentQueryService(
            KnowledgeDocumentDataService documents,
            KnowledgeDocumentViewFactory views
    ) {
        this.documents = documents;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public KnowledgeBrowseResult browse(BrowseKnowledgeDocumentsQuery query) {
        PageResult<KnowledgeDocument> page = documents.findPublished(query);
        List<String> paths = documents.findPublishedDirectoryPaths(query.context());
        List<KnowledgeDirectoryNode> directories = query.includeDescendants()
                ? directoryTree(paths)
                : directoryNodes(paths, query.directory() == null ? "" : query.directory().value());
        return new KnowledgeBrowseResult(directories, mapPage(page));
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentView get(ReadKnowledgeDocumentQuery query) {
        KnowledgeDocument document = documents.findPublishedById(query.documentId(), query.context())
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
        return views.create(document);
    }

    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocumentSummary> list(AdminKnowledgeDocumentQuery query) {
        return mapPage(documents.findAdmin(query));
    }

    /**
     * 管理员按当前上下文读取全部生命周期目录和子树分页。
     *
     * @param query 已解析上下文和目录
     * @return 管理目录及摘要页
     */
    @Transactional(readOnly = true)
    public KnowledgeBrowseResult browseAdmin(AdminBrowseKnowledgeDocumentsQuery query) {
        return new KnowledgeBrowseResult(
                directoryTree(documents.findAdminDirectoryPaths(query.context())),
                mapPage(documents.findAdmin(query)));
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentView get(Long documentId) {
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

    private List<KnowledgeDirectoryNode> directoryTree(List<String> paths) {
        Map<String, Long> counts = new LinkedHashMap<>();
        paths.stream().sorted().forEach(path -> {
            String[] parts = path.split("/");
            String current = "";
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                current = current.isEmpty() ? part : current + "/" + part;
                counts.merge(current, 1L, Long::sum);
            }
        });
        return counts.entrySet().stream()
                .map(entry -> new KnowledgeDirectoryNode(
                        entry.getKey(), entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1),
                        entry.getValue()))
                .toList();
    }
}
