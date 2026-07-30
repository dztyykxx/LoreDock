package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.ActiveCodeSnapshotDescriptor;
import io.github.loredock.code.application.CodeGenerationBuildRequest;
import io.github.loredock.code.application.CodeGenerationFile;
import io.github.loredock.code.application.CodeSnapshotChangeHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneCodeSnippetReaderTest {

    @TempDir
    Path indexRoot;

    /**
     * 业务目的：片段读取只能按固定活动范围和精确 path_exact 从 StoredField 取得正文，不读取原 ZIP 或服务器任意路径。
     */
    @Test
    void exactStoredContentRequiresAllScopeFilters() {
        Fixture fixture = publish();
        LuceneCodeSnippetReader reader = new LuceneCodeSnippetReader(new LuceneIndexHandleRegistry(indexRoot));

        assertThat(reader.read(fixture.scope, "src/A.java")).contains("one\ntwo\nthree\n");
        assertThat(reader.read(fixture.scope, "src/Missing.java")).isEmpty();
        ActiveCodeSnapshotDescriptor wrongProject = new ActiveCodeSnapshotDescriptor(
                UUID.randomUUID(), fixture.scope.branchId(), fixture.scope.snapshotId(), fixture.scope.generationId(),
                fixture.scope.commit(), fixture.scope.indexedAt(), 1, CodeSnapshotChangeHint.INITIAL);
        assertThat(reader.read(wrongProject, "src/A.java")).isEmpty();
    }

    private Fixture publish() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        new FilesystemCodeGenerationPublisher(
                indexRoot, new LuceneGenerationWriter(), new LuceneGenerationValidator(),
                GenerationDirectoryMover.atomic()).publish(new CodeGenerationBuildRequest(
                generationId, projectId, branchId, snapshotId, "abcdef1",
                List.of(new CodeGenerationFile("src/A.java", "java", "one\ntwo\nthree\n"))));
        return new Fixture(new ActiveCodeSnapshotDescriptor(
                projectId, branchId, snapshotId, generationId, "abcdef1", Instant.now(), 1,
                CodeSnapshotChangeHint.INITIAL));
    }

    private record Fixture(ActiveCodeSnapshotDescriptor scope) {
    }
}
