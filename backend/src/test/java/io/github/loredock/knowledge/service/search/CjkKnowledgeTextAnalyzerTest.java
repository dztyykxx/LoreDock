package io.github.loredock.knowledge.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.knowledge.model.result.AnalyzedKnowledgeText;
import java.util.List;
import org.junit.jupiter.api.Test;

class CjkKnowledgeTextAnalyzerTest {

    private final CjkKnowledgeTextAnalyzer analyzer = new CjkKnowledgeTextAnalyzer();

    /**
     * 业务目的：标题、标签与正文必须分别产生 CJK 词项，供 PostgreSQL 赋予 A/B/C 权重，
     * 防止字段提前混合后无法表达标题和标签优先级。
     */
    @Test
    void titleTagsAndContentRemainSeparateForWeightedIndexing() {
        AnalyzedKnowledgeText analyzed = analyzer.analyzeDocument(
                "场景导出 API", List.of("配置恢复", "MVP"), "把当前场景保存成文件并恢复。");

        assertThat(analyzed.titleTerms()).contains("场景", "导出", "api");
        assertThat(analyzed.tagTerms()).contains("配置", "恢复", "mvp");
        assertThat(analyzed.contentTerms()).contains("场景", "保存", "文件", "恢复");
        assertThat(analyzed.titleTerms()).doesNotContain("配置");
        System.out.printf("测试证据：场景=CJK字段分析，标题词项=%d，标签词项=%d，正文词项=%d%n",
                analyzed.titleTerms().size(), analyzed.tagTerms().size(), analyzed.contentTerms().size());
    }

    /**
     * 业务目的：英文缩写、单字中文和特殊字符查询必须作为纯文本分析，不能把客户端输入解释成 Lucene/SQL 查询语法。
     */
    @Test
    void abbreviationsSingleCharactersAndSpecialSyntaxArePlainTerms() {
        List<String> terms = analyzer.analyzeQuery("API 配 *:* OR title:(恢复) 单");

        assertThat(terms).contains("api", "配", "or", "title", "恢复", "单");
        assertThat(terms).doesNotContain("*:*", "title:(恢复)", "(", ")", ":");
        assertThat(analyzer.analyzeQuery("ｅ́"))
                .isEqualTo(analyzer.analyzeQuery(java.text.Normalizer.normalize("ｅ́", java.text.Normalizer.Form.NFC)));
        System.out.printf("测试证据：场景=特殊字符纯文本分析，输入长度=%d，安全词项数=%d%n",
                "API 配 *:* OR title:(恢复) 单".length(), terms.size());
    }
}
