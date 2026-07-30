package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeGenerationBuildRequest;
import io.github.loredock.code.application.CodeGenerationFile;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemCodeGenerationPublisherTest {

    @TempDir
    Path indexRoot;

    /**
     * 业务目的：只有关闭后重新打开且元数据完整的索引才能从 `.building` 原子发布为 UUID 目录。
     */
    @Test
    void validatedBuildingDirectoryIsAtomicallyPublishedAndReopenable() throws Exception {
        CodeGenerationBuildRequest request = request();
        FilesystemCodeGenerationPublisher publisher = publisher(
                new LuceneGenerationWriter(), new LuceneGenerationValidator(), GenerationDirectoryMover.atomic());

        var published = publisher.publish(request);

        Path finalDirectory = indexRoot.resolve(request.generationId().toString());
        assertThat(published.generationId()).isEqualTo(request.generationId());
        assertThat(published.documentCount()).isEqualTo(2);
        assertThat(finalDirectory).isDirectory();
        assertThat(indexRoot.resolve(request.generationId() + ".building")).doesNotExist();
        try (FSDirectory directory = FSDirectory.open(finalDirectory);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isEqualTo(2);
        }
    }

    /**
     * 业务目的：验证期间最终目录必须仍不可见，数据库因而不可能提前指向未验证的候选索引。
     */
    @Test
    void finalDirectoryRemainsInvisibleUntilValidationCompletes() throws Exception {
        CodeGenerationBuildRequest request = request();
        GenerationIndexValidator assertingValidator = (building, expected) -> {
            assertThat(building).hasFileName(request.generationId() + ".building");
            assertThat(indexRoot.resolve(request.generationId().toString())).doesNotExist();
            new LuceneGenerationValidator().validate(building, expected);
        };

        publisher(new LuceneGenerationWriter(), assertingValidator, GenerationDirectoryMover.atomic())
                .publish(request);

        assertThat(indexRoot.resolve(request.generationId().toString())).isDirectory();
    }

    /**
     * 业务目的：文档数或身份元数据不匹配必须拒绝发布并清理构建目录，不能留下可被误激活的最终目录。
     */
    @Test
    void metadataOrDocumentCountMismatchRejectsAndCleansBuildingDirectory() {
        CodeGenerationBuildRequest request = request();
        GenerationIndexWriter incompleteWriter = (directory, documents) ->
                new LuceneGenerationWriter().write(directory, documents.subList(0, 1));

        assertThatThrownBy(() -> publisher(
                incompleteWriter, new LuceneGenerationValidator(), GenerationDirectoryMover.atomic()).publish(request))
                .isInstanceOf(CodeGenerationValidationException.class);
        assertClean(request);
    }

    /**
     * 业务目的：写入、验证或原子移动任一步失败都必须幂等清理 `.building`，并且绝不能产生最终 UUID 目录。
     */
    @Test
    void writeValidationAndMoveFailuresAlwaysCleanTemporaryDirectory() {
        CodeGenerationBuildRequest writeRequest = request();
        assertThatThrownBy(() -> publisher(
                (directory, documents) -> { throw new IOException("write failed"); },
                new LuceneGenerationValidator(), GenerationDirectoryMover.atomic()).publish(writeRequest))
                .isInstanceOf(CodeGenerationPublishException.class);
        assertClean(writeRequest);

        CodeGenerationBuildRequest validationRequest = request();
        assertThatThrownBy(() -> publisher(
                new LuceneGenerationWriter(), (directory, expected) -> { throw new IOException("invalid"); },
                GenerationDirectoryMover.atomic()).publish(validationRequest))
                .isInstanceOf(CodeGenerationPublishException.class);
        assertClean(validationRequest);

        CodeGenerationBuildRequest moveRequest = request();
        assertThatThrownBy(() -> publisher(
                new LuceneGenerationWriter(), new LuceneGenerationValidator(),
                (source, target) -> { throw new IOException("move failed"); }).publish(moveRequest))
                .isInstanceOf(CodeGenerationPublishException.class);
        assertClean(moveRequest);
    }

    private FilesystemCodeGenerationPublisher publisher(
            GenerationIndexWriter writer,
            GenerationIndexValidator validator,
            GenerationDirectoryMover mover
    ) {
        return new FilesystemCodeGenerationPublisher(indexRoot, writer, validator, mover);
    }

    private CodeGenerationBuildRequest request() {
        return new CodeGenerationBuildRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "abcdef1",
                List.of(
                        new CodeGenerationFile("src/A.java", "java", "class A {}"),
                        new CodeGenerationFile("src/B.java", "java", "class B {}")));
    }

    private void assertClean(CodeGenerationBuildRequest request) {
        assertThat(Files.exists(indexRoot.resolve(request.generationId() + ".building"))).isFalse();
        assertThat(Files.exists(indexRoot.resolve(request.generationId().toString()))).isFalse();
    }
}
