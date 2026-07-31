package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.result.ActiveKnowledgeSearchGeneration;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResolvedScope;

/**
 * 两路候选读取共用的固定范围与上限。
 *
 * @param generation 搜索开始时固定的活动 generation
 * @param scope 候选读取前已解析的范围
 * @param filters 必须在候选 SQL 中应用的过滤条件
 * @param candidateLimit 服务端计算的有界候选上限，客户端不能覆盖
 */
public record KnowledgeSearchCandidateRequest(
        ActiveKnowledgeSearchGeneration generation,
        KnowledgeSearchResolvedScope scope,
        KnowledgeSearchFilters filters,
        int candidateLimit
) {
}
