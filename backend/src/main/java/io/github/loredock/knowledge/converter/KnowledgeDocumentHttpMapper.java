package io.github.loredock.knowledge.converter;

import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.response.AdminKnowledgeDocumentResponse;
import io.github.loredock.knowledge.model.response.BatchPublishKnowledgeDocumentsResponse;
import io.github.loredock.knowledge.model.response.DocumentSourceResponse;
import io.github.loredock.knowledge.model.response.KnowledgeBrowseResponse;
import io.github.loredock.knowledge.model.response.KnowledgeDirectoryNodeResponse;
import io.github.loredock.knowledge.model.response.KnowledgeDocumentResponse;
import io.github.loredock.knowledge.model.response.KnowledgeDocumentSummaryResponse;
import io.github.loredock.knowledge.model.response.KnowledgeScopeResponse;
import io.github.loredock.knowledge.model.response.PageResponse;
import io.github.loredock.knowledge.model.response.ReplacementResponse;
import io.github.loredock.knowledge.model.result.BatchPublishKnowledgeDocumentsResult;
import io.github.loredock.knowledge.model.result.KnowledgeBrowseResult;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentSummary;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.model.result.PageResult;

/**
 * 知识文档应用 DTO 到 HTTP DTO 的纯映射器，不执行范围、状态或权限判断。
 */
public final class KnowledgeDocumentHttpMapper {

    private KnowledgeDocumentHttpMapper() {
    }

    /**
     * @param result 普通浏览结果
     * @return 目录与摘要分页响应
     */
    public static KnowledgeBrowseResponse toBrowseResponse(KnowledgeBrowseResult result) {
        return new KnowledgeBrowseResponse(
                result.directories().stream().map(directory -> new KnowledgeDirectoryNodeResponse(
                        directory.path(), directory.name(), directory.documentCount())).toList(),
                toSummaryPage(result.documents()));
    }

    /**
     * @param result 已提交的批量发布数量
     * @return 不含正文的批量发布响应
     */
    public static BatchPublishKnowledgeDocumentsResponse toBatchPublishResponse(
            BatchPublishKnowledgeDocumentsResult result
    ) {
        return new BatchPublishKnowledgeDocumentsResponse(
                result.requestedCount(), result.publishedCount(), result.alreadyPublishedCount());
    }

    /**
     * @param view 已通过普通范围资格查询的详情
     * @return 不包含管理审计和替代关系的普通响应
     */
    public static KnowledgeDocumentResponse toPublicResponse(KnowledgeDocumentView view) {
        return new KnowledgeDocumentResponse(
                view.id(), view.format(), view.title(), view.body(), view.directory(),
                view.tags().stream().map(tag -> tag.displayName()).toList(),
                toSource(view.source()), toScope(view.scope()), view.status(), view.revision().value(),
                view.publishedAt(), view.updatedAt());
    }

    /**
     * @param page 应用摘要分页
     * @return HTTP 摘要分页
     */
    public static PageResponse<KnowledgeDocumentSummaryResponse> toSummaryPage(
            PageResult<KnowledgeDocumentSummary> page
    ) {
        return new PageResponse<>(
                page.items().stream().map(KnowledgeDocumentHttpMapper::toSummaryResponse).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    /**
     * @param view 管理详情应用视图
     * @return 包含生命周期、替代和同步信息的管理响应
     */
    public static AdminKnowledgeDocumentResponse toAdminResponse(KnowledgeDocumentView view) {
        return new AdminKnowledgeDocumentResponse(
                view.id(), view.format(), view.title(), view.body(), view.directory(),
                view.tags().stream().map(tag -> tag.displayName()).toList(),
                toSource(view.source()), toScope(view.scope()), view.status(), view.revision().value(),
                view.publishedAt(), view.publishedBy(), view.archivedAt(), view.archivedBy(),
                new ReplacementResponse(
                        view.replacement().replacesDocumentId(), view.replacement().replacedByDocumentId()),
                view.syncStatus(), view.createdAt(), view.updatedAt(), view.createdBy(), view.updatedBy());
    }

    private static KnowledgeDocumentSummaryResponse toSummaryResponse(KnowledgeDocumentSummary summary) {
        return new KnowledgeDocumentSummaryResponse(
                summary.id(), summary.format(), summary.title(), summary.directory(),
                summary.tags().stream().map(tag -> tag.displayName()).toList(),
                toSource(summary.source()), toScope(summary.scope()), summary.status(),
                summary.revision(), summary.syncStatus(), summary.updatedAt());
    }

    private static DocumentSourceResponse toSource(DocumentSource source) {
        return new DocumentSourceResponse(
                source.type(), source.wikiUrl(), source.originalFilename(), source.curationNote());
    }

    private static KnowledgeScopeResponse toScope(KnowledgeScope scope) {
        return new KnowledgeScopeResponse(scope.type(), scope.projectId(), scope.branchId());
    }
}
