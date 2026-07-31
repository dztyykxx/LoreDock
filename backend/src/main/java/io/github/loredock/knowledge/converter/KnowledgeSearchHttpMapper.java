package io.github.loredock.knowledge.converter;

import io.github.loredock.knowledge.model.response.KnowledgeSearchContextResponse;
import io.github.loredock.knowledge.model.response.KnowledgeSearchHttpResponse;
import io.github.loredock.knowledge.model.response.KnowledgeSearchResponse;
import io.github.loredock.knowledge.model.response.KnowledgeSearchResultResponse;
import io.github.loredock.knowledge.model.response.KnowledgeSearchResultScopeResponse;
import io.github.loredock.knowledge.model.response.KnowledgeSearchSourceResponse;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResult;

/** 知识搜索应用 DTO 到公开 HTTP DTO 的纯映射器，不执行资格过滤或重新排序。 */
public final class KnowledgeSearchHttpMapper {

    private KnowledgeSearchHttpMapper() {
    }

    /**
     * @param response 已由应用服务完成范围隔离、融合和实时资格复核的响应
     * @return 不含完整正文、向量、对象键或内部配置的 HTTP 响应
     */
    public static KnowledgeSearchHttpResponse toResponse(KnowledgeSearchResponse response) {
        return new KnowledgeSearchHttpResponse(
                new KnowledgeSearchContextResponse(
                        response.context().type(), response.context().projectIdentifier(), response.context().branch()),
                response.mode(), response.generationId(), response.warnings(),
                response.results().stream().map(KnowledgeSearchHttpMapper::toResult).toList());
    }

    private static KnowledgeSearchResultResponse toResult(KnowledgeSearchResult result) {
        return new KnowledgeSearchResultResponse(
                result.documentId(),
                new KnowledgeSearchResultScopeResponse(
                        result.scope().type(), result.scope().projectIdentifier(), result.scope().branch()),
                result.title(), result.snippet(), result.truncated(), result.format(),
                result.tags().stream().map(tag -> tag.displayName()).toList(),
                new KnowledgeSearchSourceResponse(
                        result.source().type(), result.source().wikiUrl(),
                        result.source().originalFilename(), result.source().curationNote()),
                result.sourceUpdatedAt(), result.relevance(), result.matchedBy());
    }
}
