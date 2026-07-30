package io.github.loredock.knowledge.application.search.indexing;

import java.util.List;

/** 索引与查询共用的 CJK 词项分析边界；返回纯词项而不接受客户端查询语法。 */
public interface KnowledgeTextAnalyzer {

    /**
     * @param title 文档标题
     * @param normalizedTags 规范化标签
     * @param content 当前正文分块
     * @return 保持字段边界的分析词项
     */
    AnalyzedKnowledgeText analyzeDocument(String title, List<String> normalizedTags, String content);

    /**
     * @param query 已校验纯文本查询
     * @return 与索引侧同规则产生的查询词项，不解释通配符、布尔符或字段语法
     */
    List<String> analyzeQuery(String query);
}
