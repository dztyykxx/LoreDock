package io.github.loredock.code.service.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.code.model.result.CodeIndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneGenerationWriterTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 业务目的：代码 analyzer 必须同时保留原 token 并按路径、大小写和数字边界拆词，使精确名称和自然关键词都可检索。
     */
    @Test
    void analyzerPreservesOriginalTokenAndEmitsCodeWordParts() throws Exception {
        try (CodeAnalyzer analyzer = new CodeAnalyzer()) {
            List<String> terms = analyzer.terms("path", "src/UserHTTP2Client.java");

            assertThat(terms).contains("src/userhttp2client.java", "src", "user", "http", "2", "client", "java");
        }
    }

    /**
     * 业务目的：generation 关闭后必须能重新打开，且每个文档完整保存范围、正文、路径和行数以支持后续隔离查询与片段读取。
     */
    @Test
    void writerCreatesReopenableIndexWithExactIdentityAndStoredContent() throws Exception {
        Long projectId = 8000000000000000130L;
        Long branchId = 8000000000000000131L;
        Long snapshotId = 8000000000000000132L;
        Long generationId = 8000000000000000133L;
        Path index = temporaryDirectory.resolve("generation");
        CodeIndexDocument source = new CodeIndexDocument(
                projectId, branchId, snapshotId, generationId, "abcdef1",
                "src/main/java/UserService.java", "java", "class UserService {\n  int item2Count;\n}\n");

        new LuceneGenerationWriter().write(index, List.of(source));

        try (FSDirectory directory = FSDirectory.open(index);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isEqualTo(1);
            var document = reader.storedFields().document(0);
            assertThat(document.get(CodeIndexFields.PROJECT_ID)).isEqualTo(projectId.toString());
            assertThat(document.get(CodeIndexFields.BRANCH_ID)).isEqualTo(branchId.toString());
            assertThat(document.get(CodeIndexFields.SNAPSHOT_ID)).isEqualTo(snapshotId.toString());
            assertThat(document.get(CodeIndexFields.GENERATION_ID)).isEqualTo(generationId.toString());
            assertThat(document.get(CodeIndexFields.COMMIT)).isEqualTo("abcdef1");
            assertThat(document.get(CodeIndexFields.PATH_EXACT)).isEqualTo("src/main/java/UserService.java");
            assertThat(document.get(CodeIndexFields.CONTENT)).isEqualTo(source.content());
            assertThat(document.getField(CodeIndexFields.LINE_COUNT).numericValue().intValue()).isEqualTo(3);
            assertThat(indexTerms(reader, CodeIndexFields.FILE_NAME)).contains("userservice.java", "user", "service", "java");
            assertThat(indexTerms(reader, CodeIndexFields.CONTENT)).contains("userservice", "item", "2", "count");
        }
    }

    /**
     * 业务目的：同一 generation 不能接收重复规范路径或混入其他范围，否则文档计数正确也可能造成片段读取歧义和跨范围召回。
     */
    @Test
    void writerRejectsDuplicatePathAndMixedGenerationScope() {
        Long projectId = 8000000000000000134L;
        Long branchId = 8000000000000000135L;
        Long snapshotId = 8000000000000000136L;
        Long generationId = 8000000000000000137L;
        CodeIndexDocument first = new CodeIndexDocument(
                projectId, branchId, snapshotId, generationId, "abcdef1", "src/A.java", "java", "class A {}");
        CodeIndexDocument duplicate = new CodeIndexDocument(
                projectId, branchId, snapshotId, generationId, "abcdef1", "src/A.java", "java", "class B {}");
        CodeIndexDocument mixed = new CodeIndexDocument(
                projectId, branchId, snapshotId, 8000000000000000138L, "abcdef1", "src/B.java", "java", "class B {}");

        LuceneGenerationWriter writer = new LuceneGenerationWriter();
        assertThatThrownBy(() -> writer.write(temporaryDirectory.resolve("duplicate"), List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.write(temporaryDirectory.resolve("mixed"), List.of(first, mixed)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> indexTerms(DirectoryReader reader, String field) throws Exception {
        List<String> values = new ArrayList<>();
        TermsEnum terms = MultiTerms.getTerms(reader, field).iterator();
        BytesRef value;
        while ((value = terms.next()) != null) {
            values.add(value.utf8ToString());
        }
        return values;
    }
}
