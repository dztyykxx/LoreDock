package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.request.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受控文档读取实现。复用现有知识聚合与范围 SQL，只在 Service 中补充草稿选择完整性、
 * 目录投影和有界逐行关键词匹配，不接触文件系统。
 */
@Service
public class KnowledgeDocumentAccessServiceImpl implements KnowledgeDocumentAccessService {

    private static final int MAX_SELECTED_DRAFTS = 20;
    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_GREP_LIMIT = 50;
    private static final int MAX_CONTEXT_LINES = 3;
    private static final int MAX_CONTEXT_CODE_POINTS = 1200;
    private final ProjectService projects;
    private final KnowledgeDocumentDataService documents;

    /** @param projects 项目范围契约 @param documents 知识文档数据服务 */
    public KnowledgeDocumentAccessServiceImpl(ProjectService projects, KnowledgeDocumentDataService documents) {
        this.projects = projects;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentContent> readDrafts(String projectIdentifier, List<Long> documentIds) {
        ProjectScope project = project(projectIdentifier);
        List<Long> requested = documentIds == null ? List.of() : List.copyOf(documentIds);
        if (requested.isEmpty() || requested.size() > MAX_SELECTED_DRAFTS
                || requested.stream().anyMatch(id -> id == null || id <= 0)
                || new LinkedHashSet<>(requested).size() != requested.size()) {
            throw new IllegalArgumentException("待处理草稿选择无效");
        }
        Map<Long, KnowledgeDocument> found = new LinkedHashMap<>();
        for (Long id : requested) {
            documents.findById(id).ifPresent(document -> found.put(id, document));
        }
        if (found.size() != requested.size()) {
            throw new IllegalArgumentException("待处理草稿不存在");
        }
        return requested.stream().map(found::get).map(document -> {
            boolean projectDraft = document.status() == DocumentStatus.DRAFT
                    && document.fields().format() == DocumentFormat.MARKDOWN
                    && document.fields().scope().projectId() != null
                    && Objects.equals(project.projectId(), document.fields().scope().projectId());
            if (!projectDraft) {
                throw new IllegalArgumentException("待处理草稿必须是当前项目的 Markdown DRAFT");
            }
            return content(document);
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DirectoryEntry> listPublishedDirectories(String projectIdentifier, String prefix, int limit) {
        KnowledgeBrowseContext context = context(projectIdentifier);
        String normalizedPrefix = optionalText(prefix, 255);
        int safeLimit = bounded(limit, MAX_LIST_LIMIT);
        Map<String, Long> counts = new LinkedHashMap<>();
        documents.findPublishedDirectoryPaths(context).stream().sorted().forEach(path -> {
            if (normalizedPrefix.isEmpty() || path.equals(normalizedPrefix)
                    || path.startsWith(normalizedPrefix + "/")) {
                counts.merge(path, 1L, Long::sum);
            }
        });
        return counts.entrySet().stream().limit(safeLimit)
                .map(entry -> new DirectoryEntry(entry.getKey(), entry.getValue())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentSummary> listPublishedDocuments(String projectIdentifier, String directory, int limit) {
        int safeLimit = bounded(limit, MAX_LIST_LIMIT);
        var page = documents.findPublished(new BrowseKnowledgeDocumentsQuery(
                context(projectIdentifier), directoryValue(directory), true, 0, safeLimit));
        return page.items().stream().map(document -> new DocumentSummary(
                document.id(), document.fields().title().value(), document.fields().directory().value(),
                document.updatedAt())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentContent readPublished(String projectIdentifier, Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("文档标识无效");
        }
        return documents.findPublishedById(documentId, context(projectIdentifier))
                .map(this::content)
                .orElseThrow(() -> new IllegalArgumentException("当前项目范围内不存在该已发布文档"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KeywordMatch> grepPublished(
            String projectIdentifier,
            String keyword,
            String directory,
            List<Long> documentIds,
            int limit,
            int contextLines
    ) {
        String term = requiredText(keyword, 200);
        int safeLimit = bounded(limit, MAX_GREP_LIMIT);
        int safeContext = Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));
        Set<Long> allowedIds = documentIds == null ? Set.of() : new LinkedHashSet<>(documentIds);
        if (allowedIds.stream().anyMatch(id -> id == null || id <= 0) || allowedIds.size() > 100) {
            throw new IllegalArgumentException("关键词匹配文档范围无效");
        }
        List<KnowledgeDocument> candidates = documents.findPublished(new BrowseKnowledgeDocumentsQuery(
                context(projectIdentifier), directoryValue(directory), true, 0, MAX_LIST_LIMIT)).items();
        String needle = term.toLowerCase(Locale.ROOT);
        List<KeywordMatch> result = new ArrayList<>();
        for (KnowledgeDocument document : candidates) {
            if (!allowedIds.isEmpty() && !allowedIds.contains(document.id())) {
                continue;
            }
            List<String> lines = document.fields().body().value().lines().toList();
            for (int index = 0; index < lines.size() && result.size() < safeLimit; index++) {
                if (!lines.get(index).toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                int from = Math.max(0, index - safeContext);
                int to = Math.min(lines.size(), index + safeContext + 1);
                String raw = String.join("\n", lines.subList(from, to));
                boolean truncated = raw.codePointCount(0, raw.length()) > MAX_CONTEXT_CODE_POINTS;
                result.add(new KeywordMatch(document.id(), document.fields().title().value(), index + 1,
                        truncated ? truncate(raw, MAX_CONTEXT_CODE_POINTS) : raw, truncated));
            }
            if (result.size() >= safeLimit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private KnowledgeBrowseContext context(String projectIdentifier) {
        ProjectScope project = project(projectIdentifier);
        return new KnowledgeBrowseContext(KnowledgeBrowseContextType.PROJECT, project.projectId(), project.branchId());
    }

    private ProjectScope project(String identifier) {
        return projects.resolveEnabledScope(requiredText(identifier, 64), null);
    }

    private DocumentContent content(KnowledgeDocument document) {
        return new DocumentContent(document.id(), document.revision().value(), document.fields().title().value(),
                document.fields().directory().value(), document.fields().body().value(),
                document.fields().source().originalFilename(), document.updatedAt());
    }

    private DocumentDirectory directoryValue(String value) {
        String normalized = optionalText(value, 255);
        return normalized.isEmpty() ? null : new DocumentDirectory(normalized);
    }

    private int bounded(int value, int maximum) {
        if (value <= 0) {
            throw new IllegalArgumentException("返回数量必须大于 0");
        }
        return Math.min(value, maximum);
    }

    private String requiredText(String value, int maximum) {
        String normalized = optionalText(value, maximum);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("文本参数不能为空");
        }
        return normalized;
    }

    private String optionalText(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new IllegalArgumentException("文本参数超出上限");
        }
        return normalized;
    }

    private String truncate(String value, int maximum) {
        return value.substring(0, value.offsetByCodePoints(0, maximum));
    }
}
