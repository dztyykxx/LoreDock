package io.github.loredock.code.service.index;

import io.github.loredock.code.exception.CodeIndexUnavailableException;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import java.util.Optional;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.springframework.stereotype.Component;

/** 以精确路径及四重身份 FILTER 从固定 reader 读取唯一 StoredField 正文。 */
@Component
public class LuceneCodeSnippetReader {

    private final LuceneIndexHandleRegistry handles;

    /** @param handles generation reader 引用计数注册表 */
    public LuceneCodeSnippetReader(LuceneIndexHandleRegistry handles) {
        this.handles = handles;
    }

    public Optional<String> read(ActiveCodeSnapshotDescriptor scope, String path) {
        try (LuceneIndexHandle handle = handles.acquire(scope.generationId())) {
            IndexSearcher searcher = new IndexSearcher(handle.reader());
            BooleanQuery query = new BooleanQuery.Builder()
                    .add(exact(CodeIndexFields.PATH_EXACT, path), BooleanClause.Occur.MUST)
                    // 与数据库活动解析叠加四重过滤，避免错误目录或未来共享布局造成跨范围片段读取。
                    .add(exact(CodeIndexFields.PROJECT_ID, scope.projectId().toString()), BooleanClause.Occur.FILTER)
                    .add(exact(CodeIndexFields.BRANCH_ID, scope.branchId().toString()), BooleanClause.Occur.FILTER)
                    .add(exact(CodeIndexFields.SNAPSHOT_ID, scope.snapshotId().toString()), BooleanClause.Occur.FILTER)
                    .add(exact(CodeIndexFields.GENERATION_ID, scope.generationId().toString()), BooleanClause.Occur.FILTER)
                    .build();
            var hits = searcher.search(query, 2);
            if (hits.totalHits.value() == 0) {
                return Optional.empty();
            }
            if (hits.totalHits.value() != 1) {
                throw new IllegalStateException("active code path is not unique");
            }
            return Optional.ofNullable(searcher.storedFields().document(hits.scoreDocs[0].doc)
                    .get(CodeIndexFields.CONTENT));
        } catch (CodeIndexUnavailableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CodeIndexUnavailableException(failure);
        }
    }

    private TermQuery exact(String field, String value) {
        return new TermQuery(new Term(field, value));
    }
}
