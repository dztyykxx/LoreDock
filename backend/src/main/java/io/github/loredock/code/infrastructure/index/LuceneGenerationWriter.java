package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeGenerationBuildRequest;
import io.github.loredock.code.application.CodeGenerationFileProducer;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将一个快照选择结果写入独立 Lucene generation。调用返回前 writer 与目录均已关闭，后续必须重新打开验证。
 */
public class LuceneGenerationWriter implements GenerationIndexWriter {

    /**
     * @param indexDirectory 服务端根据 generation UUID 解析出的构建目录
     * @param documents 同一项目、分支、快照、commit 和 generation 的唯一规范路径文档
     * @throws IOException Lucene 或文件系统写入失败
     */
    @Override
    public void write(Path indexDirectory, List<CodeIndexDocument> documents) throws IOException {
        validate(documents);
        Files.createDirectories(indexDirectory);
        try (CodeAnalyzer analyzer = new CodeAnalyzer();
             FSDirectory directory = FSDirectory.open(indexDirectory);
             IndexWriter writer = new IndexWriter(directory,
                     new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            for (CodeIndexDocument source : documents) {
                writer.addDocument(toLuceneDocument(source));
            }
            writer.commit();
        }
    }

    /** 生产路径在 Lucene writer 打开期间逐文件消费正文，只保留用于关闭后验证的规范路径。 */
    @Override
    public GenerationIndexWriteSummary writeStreaming(
            Path indexDirectory,
            CodeGenerationBuildRequest scope,
            CodeGenerationFileProducer producer
    ) throws IOException {
        if (!scope.files().isEmpty()) {
            throw new IllegalArgumentException("streaming generation scope must not contain files");
        }
        Files.createDirectories(indexDirectory);
        List<String> paths = new ArrayList<>();
        Set<String> uniquePaths = new HashSet<>();
        try (CodeAnalyzer analyzer = new CodeAnalyzer();
             FSDirectory directory = FSDirectory.open(indexDirectory);
             IndexWriter writer = new IndexWriter(directory,
                     new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            producer.produce(file -> {
                if (!uniquePaths.add(file.path())) {
                    throw new IllegalArgumentException("duplicate normalized code path");
                }
                writer.addDocument(toLuceneDocument(new CodeIndexDocument(
                        scope.projectId(), scope.branchId(), scope.snapshotId(), scope.generationId(),
                        scope.commit(), file.path(), file.language(), file.content())));
                paths.add(file.path());
            });
            writer.commit();
        }
        return new GenerationIndexWriteSummary(paths);
    }

    private void validate(List<CodeIndexDocument> documents) {
        if (documents == null) {
            throw new IllegalArgumentException("code index documents are required");
        }
        Set<String> paths = new HashSet<>();
        CodeIndexDocument scope = documents.isEmpty() ? null : documents.getFirst();
        for (CodeIndexDocument document : documents) {
            if (!paths.add(document.path())) {
                throw new IllegalArgumentException("duplicate normalized code path");
            }
            if (!sameScope(scope, document)) {
                throw new IllegalArgumentException("mixed code generation scope");
            }
        }
    }

    private boolean sameScope(CodeIndexDocument left, CodeIndexDocument right) {
        return left.projectId().equals(right.projectId())
                && left.branchId().equals(right.branchId())
                && left.snapshotId().equals(right.snapshotId())
                && left.generationId().equals(right.generationId())
                && left.commit().equals(right.commit());
    }

    private Document toLuceneDocument(CodeIndexDocument source) {
        Document document = new Document();
        document.add(new StringField(CodeIndexFields.PROJECT_ID, source.projectId().toString(), Field.Store.YES));
        document.add(new StringField(CodeIndexFields.BRANCH_ID, source.branchId().toString(), Field.Store.YES));
        document.add(new StringField(CodeIndexFields.SNAPSHOT_ID, source.snapshotId().toString(), Field.Store.YES));
        document.add(new StringField(CodeIndexFields.GENERATION_ID, source.generationId().toString(), Field.Store.YES));
        document.add(new StringField(CodeIndexFields.COMMIT, source.commit(), Field.Store.YES));
        document.add(new StringField(CodeIndexFields.PATH_EXACT, source.path(), Field.Store.YES));
        document.add(new SortedDocValuesField(CodeIndexFields.PATH_SORT, new BytesRef(source.path())));
        document.add(new TextField(CodeIndexFields.PATH, source.path(), Field.Store.NO));
        document.add(new TextField(CodeIndexFields.FILE_NAME, source.fileName(), Field.Store.NO));
        document.add(new StringField(CodeIndexFields.LANGUAGE, source.language(), Field.Store.YES));
        document.add(new TextField(CodeIndexFields.CONTENT, source.content(), Field.Store.YES));
        document.add(new StoredField(CodeIndexFields.LINE_COUNT, source.lineCount()));
        return document;
    }
}
