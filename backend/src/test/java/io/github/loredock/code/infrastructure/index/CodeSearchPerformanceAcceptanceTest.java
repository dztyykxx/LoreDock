package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.application.CodeGenerationBuildRequest;
import io.github.loredock.code.application.CodeGenerationFile;
import io.github.loredock.code.application.CodeSearchTarget;
import io.github.loredock.code.application.CodeSnapshotChangeHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSearchPerformanceAcceptanceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodeSearchPerformanceAcceptanceTest.class);

    @TempDir
    Path indexRoot;

    /**
     * 业务目的：确定性 20 万行 Java 模拟仓库必须可完整索引，代表性路径/内容查询在目标环境 3 秒内且跨项目严格隔离。
     */
    @Test
    void twoHundredThousandLineRepositoryMeetsSearchAndIsolationGate() {
        List<CodeGenerationFile> files = repository(200, 1_000);
        Instant indexStarted = Instant.now();
        ActiveCodeSnapshotDescriptor alpha = publish(files);
        Duration indexDuration = Duration.between(indexStarted, Instant.now());
        ActiveCodeSnapshotDescriptor beta = publish(List.of(
                new CodeGenerationFile("src/File123.java", "java", "class Beta { String betaExclusive; }")));
        LuceneCodeIndexSearcher searcher = new LuceneCodeIndexSearcher(new LuceneIndexHandleRegistry(indexRoot), 200);

        Instant contentStarted = Instant.now();
        var contentHits = searcher.search(alpha, "performanceNeedle123", CodeSearchTarget.CONTENT, null, 10);
        Duration contentDuration = Duration.between(contentStarted, Instant.now());
        Instant pathStarted = Instant.now();
        var pathHits = searcher.search(alpha, "File123", CodeSearchTarget.PATH, "src", 10);
        Duration pathDuration = Duration.between(pathStarted, Instant.now());

        assertThat(contentHits).extracting(hit -> hit.path()).contains("src/File123.java");
        assertThat(pathHits).extracting(hit -> hit.path()).containsExactly("src/File123.java");
        assertThat(searcher.search(alpha, "betaExclusive", CodeSearchTarget.CONTENT, null, 10)).isEmpty();
        assertThat(searcher.search(beta, "performanceNeedle123", CodeSearchTarget.CONTENT, null, 10)).isEmpty();
        assertThat(contentDuration).isLessThan(Duration.ofSeconds(3));
        assertThat(pathDuration).isLessThan(Duration.ofSeconds(3));
        assertThat(indexDuration).isLessThan(Duration.ofSeconds(60));
        LOGGER.info("code_search_acceptance lines=200000 documents={} indexMs={} contentQueryMs={} pathQueryMs={}",
                files.size(), indexDuration.toMillis(), contentDuration.toMillis(), pathDuration.toMillis());
    }

    private List<CodeGenerationFile> repository(int fileCount, int linesPerFile) {
        List<CodeGenerationFile> files = new ArrayList<>(fileCount);
        for (int file = 0; file < fileCount; file++) {
            StringBuilder content = new StringBuilder(linesPerFile * 72);
            for (int line = 0; line < linesPerFile; line++) {
                content.append("public void method").append(line)
                        .append("(){ String value = \"performanceNeedle").append(file).append("\"; }\n");
            }
            files.add(new CodeGenerationFile("src/File" + file + ".java", "java", content.toString()));
        }
        return files;
    }

    private ActiveCodeSnapshotDescriptor publish(List<CodeGenerationFile> files) {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        new FilesystemCodeGenerationPublisher(
                indexRoot, new LuceneGenerationWriter(), new LuceneGenerationValidator(),
                GenerationDirectoryMover.atomic()).publish(new CodeGenerationBuildRequest(
                generationId, projectId, branchId, snapshotId, "abcdef1", files));
        return new ActiveCodeSnapshotDescriptor(
                projectId, branchId, snapshotId, generationId, "abcdef1", Instant.now(), files.size(),
                CodeSnapshotChangeHint.INITIAL);
    }
}
