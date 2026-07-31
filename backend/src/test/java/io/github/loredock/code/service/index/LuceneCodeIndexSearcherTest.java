package io.github.loredock.code.service.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.code.model.enums.CodeSearchTarget;
import io.github.loredock.code.model.enums.CodeSnapshotChangeHint;
import io.github.loredock.code.model.request.CodeGenerationBuildRequest;
import io.github.loredock.code.model.result.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.model.result.CodeGenerationFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneCodeIndexSearcherTest {

    @TempDir
    Path indexRoot;
    private long nextId = 8000000000000000119L;

    /**
     * 业务目的：两个项目同名分支和文件必须由各自固定 generation 与 Lucene 身份 FILTER 双重隔离，不能跨范围召回。
     */
    @Test
    void exactScopeFiltersIsolateSameBranchAndPathAcrossProjects() {
        Fixture alpha = publish("alphaOnlyToken");
        Fixture beta = publish("betaOnlyToken");
        LuceneCodeIndexSearcher searcher = searcher();

        assertThat(searcher.search(alpha.descriptor, "alphaOnlyToken", CodeSearchTarget.ALL, null, 10))
                .extracting(hit -> hit.path()).containsExactly("src/Same.java");
        assertThat(searcher.search(alpha.descriptor, "betaOnlyToken", CodeSearchTarget.ALL, null, 10)).isEmpty();
        assertThat(searcher.search(beta.descriptor, "betaOnlyToken", CodeSearchTarget.ALL, null, 10))
                .extracting(hit -> hit.path()).containsExactly("src/Same.java");
    }

    /**
     * 业务目的：ALL、PATH、CONTENT 与 pathPrefix 必须由服务端构造查询，特殊字符只能作为文本分析而不能注入 QueryParser 语法。
     */
    @Test
    void targetPrefixAndSpecialCharactersUseProgrammaticQueries() {
        Fixture fixture = publish(List.of(
                new CodeGenerationFile("src/UserService.java", "java", "class Service {}"),
                new CodeGenerationFile("test/Other.java", "java", "String userService = \"x\";")));
        LuceneCodeIndexSearcher searcher = searcher();

        assertThat(searcher.search(fixture.descriptor, "UserService", CodeSearchTarget.PATH, null, 10))
                .extracting(hit -> hit.path()).containsExactly("src/UserService.java");
        assertThat(searcher.search(fixture.descriptor, "UserService", CodeSearchTarget.CONTENT, null, 10))
                .extracting(hit -> hit.path()).containsExactly("test/Other.java");
        assertThat(searcher.search(fixture.descriptor, "UserService", CodeSearchTarget.ALL, "src", 10))
                .extracting(hit -> hit.path()).containsExactly("src/UserService.java");
        assertThat(searcher.search(fixture.descriptor, "userService:(*)", CodeSearchTarget.ALL, null, 10))
                .extracting(hit -> hit.path()).contains("src/UserService.java", "test/Other.java");
    }

    /**
     * 业务目的：ALL 模式路径/文件名权重高于正文，分数相同再按路径正序，片段保持有限纯文本且不生成 HTML 标记。
     */
    @Test
    void resultsUsePathBoostStableOrderingAndBoundedPlainTextSnippet() {
        Fixture fixture = publish(List.of(
                new CodeGenerationFile("src/UserService.java", "java", "class Service {}"),
                new CodeGenerationFile("src/A.java", "java", "userService ".repeat(30)),
                new CodeGenerationFile("src/B.java", "java", "userService ".repeat(30))));

        var hits = searcher().search(fixture.descriptor, "userService", CodeSearchTarget.ALL, null, 10);

        assertThat(hits.getFirst().path()).isEqualTo("src/UserService.java");
        assertThat(hits.subList(1, 3)).extracting(hit -> hit.path()).containsExactly("src/A.java", "src/B.java");
        assertThat(hits.get(1).snippet()).hasSizeLessThanOrEqualTo(80).doesNotContain("<", ">");
        assertThat(hits.get(1).truncated()).isTrue();
    }

    private LuceneCodeIndexSearcher searcher() {
        return new LuceneCodeIndexSearcher(new LuceneIndexHandleRegistry(indexRoot), 80);
    }

    private Fixture publish(String token) {
        return publish(List.of(new CodeGenerationFile("src/Same.java", "java", "class " + token + " {}")));
    }

    private Fixture publish(List<CodeGenerationFile> files) {
        Long projectId = nextId++;
        Long branchId = nextId++;
        Long snapshotId = nextId++;
        Long generationId = nextId++;
        new FilesystemCodeGenerationPublisher(
                indexRoot, new LuceneGenerationWriter(), new LuceneGenerationValidator(),
                FilesystemCodeGenerationPublisher.DirectoryMover.atomic()).publish(new CodeGenerationBuildRequest(
                generationId, projectId, branchId, snapshotId, "abcdef1", files));
        return new Fixture(new ActiveCodeSnapshotDescriptor(
                projectId, branchId, snapshotId, generationId, "abcdef1", Instant.now(), files.size(),
                CodeSnapshotChangeHint.INITIAL));
    }

    private record Fixture(ActiveCodeSnapshotDescriptor descriptor) {
    }
}
