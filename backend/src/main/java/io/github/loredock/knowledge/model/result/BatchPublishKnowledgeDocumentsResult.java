package io.github.loredock.knowledge.model.result;

/**
 * 批量发布结果，只返回可核验数量；页面随后重读服务端文档事实。
 *
 * @param requestedCount 请求中的唯一文档数
 * @param publishedCount 本次从草稿转为已发布的文档数
 * @param alreadyPublishedCount 提交前已经发布且保持幂等的文档数
 */
public record BatchPublishKnowledgeDocumentsResult(
        int requestedCount,
        int publishedCount,
        int alreadyPublishedCount
) {
}
