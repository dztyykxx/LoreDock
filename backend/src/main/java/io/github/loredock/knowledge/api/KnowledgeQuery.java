package io.github.loredock.knowledge.api;

/**
 * 跨模块知识检索请求。
 *
 * @param projectIdentifier 已启用项目标识
 * @param branch 服务端解析后的实际分支
 * @param query 用户检索文本
 * @param limit 服务端允许范围内的返回上限
 * @param indexVersionId 运行开始时固定的不透明活动索引版本
 */
public record KnowledgeQuery(
        String projectIdentifier,
        String branch,
        String query,
        int limit,
        Long indexVersionId
) {
}
