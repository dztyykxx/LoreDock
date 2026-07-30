package io.github.loredock.knowledge.application.search;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 折叠到文档级的有限可引用搜索结果，不携带完整正文、对象键、向量或内部配置。
 *
 * @param documentId 稳定文档标识
 * @param scope 当前结果范围
 * @param title 标题
 * @param snippet 最多 500 个 Unicode 字符的 generation 内片段
 * @param truncated 片段是否因上限截断
 * @param format 文档格式
 * @param tags 标签
 * @param source 可公开来源字段
 * @param sourceUpdatedAt 投影来源更新时间
 * @param relevance 当前 generation 内 0～1 的归一化相关性
 * @param matchedBy 实际候选来源
 */
public record KnowledgeSearchResult(
        UUID documentId,
        KnowledgeSearchResultScope scope,
        String title,
        String snippet,
        boolean truncated,
        DocumentFormat format,
        List<DocumentTag> tags,
        DocumentSource source,
        Instant sourceUpdatedAt,
        double relevance,
        KnowledgeSearchMatchedBy matchedBy
) {
    public KnowledgeSearchResult {
        tags = List.copyOf(tags);
    }
}
