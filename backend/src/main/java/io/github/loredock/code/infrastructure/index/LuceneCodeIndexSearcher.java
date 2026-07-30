package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.application.CodeIndexSearchHit;
import io.github.loredock.code.application.CodeIndexSearchPort;
import io.github.loredock.code.application.CodeIndexUnavailableException;
import io.github.loredock.code.application.CodeSearchTarget;
import io.github.loredock.code.infrastructure.CodeSnapshotProperties;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.util.QueryBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 使用服务端分析词项和强制身份 FILTER 查询固定 generation 的 Lucene 适配器。 */
@Component
public class LuceneCodeIndexSearcher implements CodeIndexSearchPort {

    private final LuceneIndexHandleRegistry handles;
    private final int snippetChars;

    /** 生产构造器。 */
    @Autowired
    public LuceneCodeIndexSearcher(LuceneIndexHandleRegistry handles, CodeSnapshotProperties properties) {
        this(handles, properties.maxSearchSnippetChars());
    }

    LuceneCodeIndexSearcher(LuceneIndexHandleRegistry handles, int snippetChars) {
        this.handles = handles;
        this.snippetChars = snippetChars;
    }

    @Override
    public List<CodeIndexSearchHit> search(
            ActiveCodeSnapshotDescriptor scope,
            String queryText,
            CodeSearchTarget target,
            String pathPrefix,
            int limit
    ) {
        try (LuceneIndexHandle handle = handles.acquire(scope.generationId());
             CodeAnalyzer analyzer = new CodeAnalyzer()) {
            IndexSearcher searcher = new IndexSearcher(handle.reader());
            Query contentQuery = textQuery(analyzer, CodeIndexFields.CONTENT, queryText);
            Query userQuery = switch (target) {
                case PATH -> boostedPathQuery(analyzer, queryText);
                case CONTENT -> contentQuery;
                case ALL -> allFieldsQuery(analyzer, queryText, contentQuery);
            };
            BooleanQuery.Builder scoped = new BooleanQuery.Builder().add(userQuery, BooleanClause.Occur.MUST);
            // 数据库已固定活动描述符，Lucene 仍使用四重 FILTER，防止目录放置错误或未来布局变化导致跨范围召回。
            scoped.add(exact(CodeIndexFields.PROJECT_ID, scope.projectId().toString()), BooleanClause.Occur.FILTER)
                    .add(exact(CodeIndexFields.BRANCH_ID, scope.branchId().toString()), BooleanClause.Occur.FILTER)
                    .add(exact(CodeIndexFields.SNAPSHOT_ID, scope.snapshotId().toString()), BooleanClause.Occur.FILTER)
                    .add(exact(CodeIndexFields.GENERATION_ID, scope.generationId().toString()), BooleanClause.Occur.FILTER);
            if (pathPrefix != null) {
                scoped.add(new PrefixQuery(new Term(CodeIndexFields.PATH_EXACT, pathPrefix)),
                        BooleanClause.Occur.FILTER);
            }
            Sort sort = new Sort(SortField.FIELD_SCORE, new SortField(CodeIndexFields.PATH_SORT, SortField.Type.STRING));
            var topDocs = searcher.search(scoped.build(), limit, sort, true);
            List<CodeIndexSearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            for (var scoreDoc : topDocs.scoreDocs) {
                Document document = searcher.storedFields().document(scoreDoc.doc);
                String content = document.get(CodeIndexFields.CONTENT);
                String snippet = snippet(analyzer, contentQuery, content);
                hits.add(new CodeIndexSearchHit(
                        document.get(CodeIndexFields.PATH_EXACT), snippet, scoreDoc.score,
                        snippet.length() < content.length()));
            }
            return List.copyOf(hits);
        } catch (CodeIndexUnavailableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CodeIndexUnavailableException(failure);
        }
    }

    private Query allFieldsQuery(CodeAnalyzer analyzer, String text, Query contentQuery) throws Exception {
        return new BooleanQuery.Builder()
                .add(new BoostQuery(textQuery(analyzer, CodeIndexFields.FILE_NAME, text), 4f), BooleanClause.Occur.SHOULD)
                .add(new BoostQuery(textQuery(analyzer, CodeIndexFields.PATH, text), 2f), BooleanClause.Occur.SHOULD)
                .add(contentQuery, BooleanClause.Occur.SHOULD)
                .setMinimumNumberShouldMatch(1)
                .build();
    }

    private Query boostedPathQuery(CodeAnalyzer analyzer, String text) throws Exception {
        return new BooleanQuery.Builder()
                .add(new BoostQuery(textQuery(analyzer, CodeIndexFields.FILE_NAME, text), 2f), BooleanClause.Occur.SHOULD)
                .add(textQuery(analyzer, CodeIndexFields.PATH, text), BooleanClause.Occur.SHOULD)
                .setMinimumNumberShouldMatch(1)
                .build();
    }

    private Query textQuery(CodeAnalyzer analyzer, String field, String text) throws Exception {
        Query query = new QueryBuilder(analyzer).createBooleanQuery(field, text, BooleanClause.Occur.MUST);
        return query == null ? new MatchNoDocsQuery("query produced no code terms") : query;
    }

    private Query exact(String field, String value) {
        return new TermQuery(new Term(field, value));
    }

    private String snippet(CodeAnalyzer analyzer, Query contentQuery, String content) throws Exception {
        Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter("", ""), new QueryScorer(contentQuery));
        highlighter.setTextFragmenter(new SimpleFragmenter(snippetChars));
        highlighter.setMaxDocCharsToAnalyze(content.length());
        String highlighted = highlighter.getBestFragment(analyzer, CodeIndexFields.CONTENT, content);
        String plain = highlighted == null ? content : highlighted;
        return plain.length() <= snippetChars ? plain : plain.substring(0, snippetChars);
    }
}
