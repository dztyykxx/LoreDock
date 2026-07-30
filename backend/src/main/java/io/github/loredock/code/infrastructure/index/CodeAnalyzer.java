package io.github.loredock.code.infrastructure.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 面向代码标识符与仓库路径的 analyzer：保留完整 token，并按分隔符、大小写和数字边界拆分后统一小写。
 */
public class CodeAnalyzer extends Analyzer {

    private static final int WORD_RULES = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
            | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
            | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
            | WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
            | WordDelimiterGraphFilter.PRESERVE_ORIGINAL;

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();
        TokenStream parts = new WordDelimiterGraphFilter(tokenizer, WORD_RULES, null);
        return new TokenStreamComponents(tokenizer, new LowerCaseFilter(parts));
    }

    /** 包内测试和后续服务端查询构造共用的分析结果；客户端文本永不进入 QueryParser。 */
    List<String> terms(String field, String value) throws IOException {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = tokenStream(field, value)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        }
        return terms;
    }
}
