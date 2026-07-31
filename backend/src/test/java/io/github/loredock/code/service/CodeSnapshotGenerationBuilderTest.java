package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.loredock.code.model.enums.CodeFileIgnoreReason;
import io.github.loredock.code.model.enums.CodeSnapshotStatus;
import io.github.loredock.code.model.request.CodeGenerationBuildRequest;
import io.github.loredock.code.model.result.CodeArchiveEntry;
import io.github.loredock.code.model.result.CodeFileSelection;
import io.github.loredock.code.model.result.CodeSnapshotGenerationResult;
import io.github.loredock.code.model.result.CodeSnapshotRecord;
import io.github.loredock.code.model.result.PublishedCodeGeneration;
import io.github.loredock.code.service.archive.CommonsCompressCodeArchiveReader;
import io.github.loredock.code.service.archive.DefaultCodeFileSelector;
import io.github.loredock.code.service.index.FilesystemCodeGenerationPublisher;
import io.github.loredock.job.api.JobService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CodeSnapshotGenerationBuilderTest {

    /**
     * 业务目的：安全选择后的正文必须在归档回调内直接交给 writer，不能先聚合整个仓库；忽略文件仍要进入稳定计数。
     */
    @Test
    void selectedFilesFlowDirectlyToStreamingPublisherAndIgnoredCountIsPreserved() {
        Long generationId = 8000000000000000065L;
        long[] consumed = {0};
        FilesystemCodeGenerationPublisher publisher = mock(FilesystemCodeGenerationPublisher.class);
        doAnswer(invocation -> {
            CodeGenerationBuildRequest scope = invocation.getArgument(0);
            FilesystemCodeGenerationPublisher.FileProducer producer = invocation.getArgument(1);
            assertThat(scope.files()).isEmpty();
            producer.produce(file -> {
                consumed[0]++;
                assertThat(file.path()).isEqualTo("src/App.java");
                assertThat(file.content()).isEqualTo("class App {}");
            });
            return new PublishedCodeGeneration(scope.generationId(), consumed[0]);
        }).when(publisher).publishStreaming(any(), any());
        CommonsCompressCodeArchiveReader archive = mock(CommonsCompressCodeArchiveReader.class);
        doAnswer(invocation -> {
            CommonsCompressCodeArchiveReader.EntryConsumer consumer = invocation.getArgument(2);
            accept(consumer, "src/App.java", "class App {}");
            accept(consumer, ".env", "TOKEN=example");
            return null;
        }).when(archive).read(any(), any(), any());
        DefaultCodeFileSelector selector = mock(DefaultCodeFileSelector.class);
        doAnswer(invocation -> {
            CodeArchiveEntry entry = invocation.getArgument(0);
            java.io.InputStream input = invocation.getArgument(1);
            return entry.path().equals(".env")
                    ? CodeFileSelection.ignored(entry.path(), CodeFileIgnoreReason.SENSITIVE_PATH)
                    : CodeFileSelection.selected(entry.path(), read(input));
        }).when(selector).select(any(), any());
        CodeSnapshotGenerationBuilder builder = new CodeSnapshotGenerationBuilder(archive, selector, publisher);

        CodeSnapshotGenerationResult result = builder.build(new RecordingContext(), snapshot(), generationId);

        assertThat(result.indexedFileCount()).isEqualTo(1);
        assertThat(result.ignoredFileCount()).isEqualTo(1);
        assertThat(consumed[0]).isEqualTo(1);
    }

    private void accept(CommonsCompressCodeArchiveReader.EntryConsumer consumer, String path, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            consumer.accept(new CodeArchiveEntry(path, bytes.length, bytes.length),
                    new ByteArrayInputStream(bytes));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private String read(java.io.InputStream input) {
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private CodeSnapshotRecord snapshot() {
        return new CodeSnapshotRecord(
                8000000000000000066L, 8000000000000000067L, 8000000000000000068L, "abcdef1", "object-key",
                CodeSnapshotStatus.CANDIDATE, null, 0, 0, null, null);
    }

    private static final class RecordingContext implements JobService.ExecutionContext {
        private final Long jobId = 8000000000000000069L;

        @Override
        public Long jobId() {
            return jobId;
        }

        @Override
        public String inputObjectKey() {
            return "object-key";
        }

        @Override
        public void updateProgress(int progress) {
            // 该测试只保护流式正文所有权；进度单调性由后台任务测试保护。
        }

        @Override
        public void heartbeat() {
            // 该测试只保护流式正文所有权；心跳持久化由后台任务测试保护。
        }
    }
}
