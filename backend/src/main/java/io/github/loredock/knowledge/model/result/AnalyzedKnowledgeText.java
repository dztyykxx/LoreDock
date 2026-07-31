package io.github.loredock.knowledge.model.result;

import java.util.List;

/**
 * 标题、标签与正文独立分析后的安全词项；字段分离用于数据库 A/B/C 权重。
 *
 * @param titleTerms 标题词项
 * @param tagTerms 标签词项
 * @param contentTerms 当前分块正文词项
 */
public record AnalyzedKnowledgeText(
        List<String> titleTerms,
        List<String> tagTerms,
        List<String> contentTerms
) {
    public AnalyzedKnowledgeText {
        titleTerms = List.copyOf(titleTerms);
        tagTerms = List.copyOf(tagTerms);
        contentTerms = List.copyOf(contentTerms);
    }
}
