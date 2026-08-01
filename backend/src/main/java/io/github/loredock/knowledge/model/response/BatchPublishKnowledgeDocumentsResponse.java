package io.github.loredock.knowledge.model.response;

/**
 * 批量发布 HTTP 结果；不返回正文或替代关系。
 *
 * @param requestedCount 请求文档数
 * @param publishedCount 本次新发布数
 * @param alreadyPublishedCount 幂等保持已发布数
 */
public record BatchPublishKnowledgeDocumentsResponse(
        int requestedCount,
        int publishedCount,
        int alreadyPublishedCount
) {
}
