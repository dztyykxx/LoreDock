package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.enums.KnowledgeSearchMatchedBy;

/**
 * 已折叠到文档级、等待实时资格复核的融合候选。
 *
 * @param documentId 文档标识
 * @param bestCandidate 固定 generation 中用于引用元数据和片段的最佳分块
 * @param snippet 最多 500 个 Unicode code point 的片段
 * @param truncated 片段是否因上限截断
 * @param relevance 当前 generation 内归一到 0～1 的 RRF 相关性
 * @param matchedBy 实际命中的候选通道
 */
public record FusedKnowledgeSearchCandidate(
        Long documentId,
        KnowledgeSearchCandidate bestCandidate,
        String snippet,
        boolean truncated,
        double relevance,
        KnowledgeSearchMatchedBy matchedBy
) {
}
