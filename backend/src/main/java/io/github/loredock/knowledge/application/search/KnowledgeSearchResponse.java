package io.github.loredock.knowledge.application.search;

import java.util.List;
import java.util.UUID;

/**
 * 固定单一活动 generation 的知识检索响应；无命中返回空结果且不扩大范围。
 *
 * @param context 服务端解析后的实际上下文
 * @param mode 实际模式
 * @param generationId 本次请求固定使用的活动 generation
 * @param warnings 不阻止人工知识返回的范围警告
 * @param results 稳定排序后的有界文档结果
 */
public record KnowledgeSearchResponse(
        KnowledgeSearchContext context,
        KnowledgeSearchMode mode,
        UUID generationId,
        List<KnowledgeSearchWarning> warnings,
        List<KnowledgeSearchResult> results
) {
    public KnowledgeSearchResponse {
        warnings = List.copyOf(warnings);
        results = List.copyOf(results);
    }
}
