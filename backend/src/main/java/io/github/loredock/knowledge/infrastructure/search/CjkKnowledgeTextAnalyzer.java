package io.github.loredock.knowledge.infrastructure.search;

import io.github.loredock.knowledge.application.search.indexing.AnalyzedKnowledgeText;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeTextAnalyzer;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用 Lucene CJKAnalyzer 统一分析入库字段和用户查询。
 *
 * <p>输入仅作为普通文本分词，不经过 Lucene QueryParser 或 SQL 语法解析；标题、标签、正文
 * 分开返回，以便 PostgreSQL 分别赋予 A、B、C 权重。</p>
 */
@Component
public final class CjkKnowledgeTextAnalyzer implements KnowledgeTextAnalyzer, AutoCloseable {

    // 禁用默认英文停用词，确保 OR 等输入只作为普通词项，而不是被查询语法或隐式规则吞掉。
    private final CJKAnalyzer analyzer = new CJKAnalyzer(CharArraySet.EMPTY_SET);

    @Override
    public AnalyzedKnowledgeText analyzeDocument(
            String title,
            List<String> normalizedTags,
            String content
    ) {
        List<String> tagTerms = new ArrayList<>();
        for (String normalizedTag : normalizedTags == null ? List.<String>of() : normalizedTags) {
            tagTerms.addAll(analyze(normalizedTag));
        }
        return new AnalyzedKnowledgeText(
                analyze(title),
                tagTerms,
                analyze(content)
        );
    }

    @Override
    public List<String> analyzeQuery(String query) {
        return analyze(query);
    }

    private List<String> analyze(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        List<String> terms = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream("knowledge", normalized)) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                terms.add(term.toString());
            }
            tokenStream.end();
            return List.copyOf(terms);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to analyze knowledge text", exception);
        }
    }

    /** 释放 Lucene 分析器持有的资源。 */
    @Override
    @PreDestroy
    public void close() {
        analyzer.close();
    }
}
