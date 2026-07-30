package io.github.loredock.knowledge.application.search;

import java.util.List;

/** 在固定 generation 和强范围内执行精确余弦语义候选查询的应用输出端口。 */
public interface KnowledgeSemanticCandidatePort {

    /**
     * 实现必须在向量排序前应用 generation、范围、标签、格式、来源和候选上限。
     *
     * @param request 固定 generation、范围、过滤和候选上限
     * @param queryEmbedding 与活动 generation 模型及维度匹配的查询向量
     * @return 按余弦分数、文档 ID、分块序号稳定排序的有限候选
     */
    List<KnowledgeSearchCandidate> findCandidates(
            KnowledgeSearchCandidateRequest request,
            KnowledgeEmbeddingVector queryEmbedding
    );
}
