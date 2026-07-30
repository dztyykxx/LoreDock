package io.github.loredock.code.application;

import io.github.loredock.code.domain.CodeSnapshotStatus;
import io.github.loredock.job.application.JobExecutionContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSnapshotGenerationBuilderTest {

    /**
     * 业务目的：安全选择后的正文必须在归档回调内直接交给 writer，不能先聚合整个仓库；忽略文件仍要进入稳定计数。
     */
    @Test
    void selectedFilesFlowDirectlyToStreamingPublisherAndIgnoredCountIsPreserved() {
        UUID generationId = UUID.randomUUID();
        long[] consumed = {0};
        CodeGenerationPublishPort publisher = new CodeGenerationPublishPort() {
            @Override
            public PublishedCodeGeneration publish(CodeGenerationBuildRequest request) {
                throw new AssertionError("构建器不得使用聚合正文发布入口");
            }

            @Override
            public PublishedCodeGeneration publishStreaming(
                    CodeGenerationBuildRequest scope,
                    CodeGenerationFileProducer producer
            ) {
                assertThat(scope.files()).isEmpty();
                producer.produce(file -> {
                    consumed[0]++;
                    assertThat(file.path()).isEqualTo("src/App.java");
                    assertThat(file.content()).isEqualTo("class App {}");
                });
                return new PublishedCodeGeneration(scope.generationId(), consumed[0]);
            }
        };
        CodeArchiveReadPort archive = (jobId, objectKey, consumer) -> {
            accept(consumer, "src/App.java", "class App {}");
            accept(consumer, ".env", "TOKEN=example");
        };
        CodeFileSelector selector = (entry, input) -> entry.path().equals(".env")
                ? CodeFileSelection.ignored(entry.path(), CodeFileIgnoreReason.SENSITIVE_PATH)
                : CodeFileSelection.selected(entry.path(), read(input));
        CodeSnapshotGenerationBuilder builder = new CodeSnapshotGenerationBuilder(archive, selector, publisher);

        CodeSnapshotGenerationResult result = builder.build(new RecordingContext(), snapshot(), generationId);

        assertThat(result.indexedFileCount()).isEqualTo(1);
        assertThat(result.ignoredFileCount()).isEqualTo(1);
        assertThat(consumed[0]).isEqualTo(1);
    }

    private void accept(CodeArchiveEntryConsumer consumer, String path, String content) {
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
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "abcdef1", "object-key",
                CodeSnapshotStatus.CANDIDATE, null, 0, 0, null, null);
    }

    private static final class RecordingContext implements JobExecutionContext {
        private final UUID jobId = UUID.randomUUID();

        @Override
        public UUID jobId() {
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
