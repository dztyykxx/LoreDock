package io.github.loredock.code.service.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.code.model.request.CodeGenerationBuildRequest;
import io.github.loredock.code.model.result.CodeGenerationFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneIndexHandleRegistryTest {

    @TempDir
    Path indexRoot;

    /**
     * 业务目的：活动 reader 被请求持有时退休不能删除目录，已开始请求仍能读完并在最后引用释放后清理。
     */
    @Test
    void retiredGenerationWaitsForLastReaderBeforeDeletion() {
        Long generationId = publishGeneration();
        LuceneIndexHandleRegistry registry = new LuceneIndexHandleRegistry(indexRoot);
        LuceneIndexHandle handle = registry.acquire(generationId);

        registry.retire(generationId);

        assertThat(indexRoot.resolve(generationId.toString())).isDirectory();
        assertThat(handle.reader().numDocs()).isEqualTo(1);
        handle.close();
        assertThat(indexRoot.resolve(generationId.toString())).doesNotExist();
    }

    /**
     * 业务目的：退休目录清理失败不得关闭仍服务查询的 reader 或让调用方请求失败，后续退休调用可以幂等重试。
     */
    @Test
    void cleanupFailureDoesNotBreakQueryAndCanBeRetried() {
        Long generationId = publishGeneration();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        LuceneIndexHandleRegistry.DirectoryCleaner cleaner = directory -> {
            if (fail.getAndSet(false)) {
                throw new IOException("simulated cleanup failure");
            }
            LuceneIndexHandleRegistry.DirectoryCleaner.recursive().delete(directory);
        };
        LuceneIndexHandleRegistry registry = new LuceneIndexHandleRegistry(indexRoot, cleaner);
        LuceneIndexHandle handle = registry.acquire(generationId);
        registry.retire(generationId);
        handle.close();

        assertThat(indexRoot.resolve(generationId.toString())).isDirectory();
        registry.retire(generationId);
        assertThat(indexRoot.resolve(generationId.toString())).doesNotExist();
    }

    private Long publishGeneration() {
        Long generationId = 8000000000000000126L;
        CodeGenerationBuildRequest request = new CodeGenerationBuildRequest(
                generationId, 8000000000000000127L, 8000000000000000128L, 8000000000000000129L, "abcdef1",
                List.of(new CodeGenerationFile("src/A.java", "java", "class A {}")));
        new FilesystemCodeGenerationPublisher(
                indexRoot, new LuceneGenerationWriter(), new LuceneGenerationValidator(),
                FilesystemCodeGenerationPublisher.DirectoryMover.atomic()).publish(request);
        return generationId;
    }
}
