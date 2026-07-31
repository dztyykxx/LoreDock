package io.github.loredock.code.service.index;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.CodeGenerationPublishException;
import io.github.loredock.code.exception.CodeGenerationValidationException;
import io.github.loredock.code.model.request.CodeGenerationBuildRequest;
import io.github.loredock.code.model.result.CodeGenerationFile;
import io.github.loredock.code.model.result.CodeIndexDocument;
import io.github.loredock.code.model.result.GenerationIndexWriteSummary;
import io.github.loredock.code.model.result.PublishedCodeGeneration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 基于同一索引根原子重命名的 generation 发布器。任何失败都会清理本次 Long 对应的构建和未引用最终目录。
 */
@Component
public class FilesystemCodeGenerationPublisher {

    /** 在 writer 生命周期内逐文件消费已完成安全选择的正文。 */
    @FunctionalInterface
    public interface FileConsumer {
        void accept(CodeGenerationFile file) throws IOException;
    }

    /** 在 writer 生命周期内逐文件生产正文，避免聚合整个仓库内容。 */
    @FunctionalInterface
    public interface FileProducer {
        void produce(FileConsumer consumer);
    }

    /** Lucene writer 的模块内部故障注入点，不作为业务替换边界暴露。 */
    @FunctionalInterface
    interface IndexWriter {
        void write(Path directory, List<CodeIndexDocument> documents) throws IOException;

        default GenerationIndexWriteSummary writeStreaming(
                Path directory,
                CodeGenerationBuildRequest scope,
                FileProducer producer
        ) throws IOException {
            List<CodeIndexDocument> documents = new ArrayList<>();
            producer.produce(file -> documents.add(new CodeIndexDocument(
                    scope.projectId(), scope.branchId(), scope.snapshotId(), scope.generationId(),
                    scope.commit(), file.path(), file.language(), file.content())));
            write(directory, documents);
            return new GenerationIndexWriteSummary(documents.stream().map(CodeIndexDocument::path).toList());
        }
    }

    /** 发布前重开验证的模块内部故障注入点。 */
    @FunctionalInterface
    interface IndexValidator {
        void validate(Path directory, CodeGenerationBuildRequest expected) throws IOException;
    }

    /** 同一索引根内原子发布目录的模块内部故障注入点。 */
    @FunctionalInterface
    interface DirectoryMover {
        void move(Path source, Path target) throws IOException;

        static DirectoryMover atomic() {
            return (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private final Path indexRoot;
    private final FilesystemCodeGenerationPublisher.IndexWriter writer;
    private final FilesystemCodeGenerationPublisher.IndexValidator validator;
    private final FilesystemCodeGenerationPublisher.DirectoryMover mover;

    /** @param properties 已验证且与对象根不重叠的代码索引配置 */
    @Autowired
    public FilesystemCodeGenerationPublisher(CodeSnapshotProperties properties) {
        this(properties.indexRoot(), new LuceneGenerationWriter(),
                new LuceneGenerationValidator(), FilesystemCodeGenerationPublisher.DirectoryMover.atomic());
    }

    FilesystemCodeGenerationPublisher(
            Path indexRoot,
            FilesystemCodeGenerationPublisher.IndexWriter writer,
            FilesystemCodeGenerationPublisher.IndexValidator validator,
            FilesystemCodeGenerationPublisher.DirectoryMover mover
    ) {
        this.indexRoot = indexRoot.toAbsolutePath().normalize();
        this.writer = writer;
        this.validator = validator;
        this.mover = mover;
    }

    public PublishedCodeGeneration publish(CodeGenerationBuildRequest request) {
        CodeGenerationBuildRequest scope = new CodeGenerationBuildRequest(
                request.generationId(), request.projectId(), request.branchId(), request.snapshotId(),
                request.commit(), java.util.List.of());
        return publishStreaming(scope, consumer -> {
            for (CodeGenerationFile file : request.files()) {
                try {
                    consumer.accept(file);
                } catch (IOException failure) {
                    throw new CodeGenerationPublishException(failure);
                }
            }
        });
    }

    public PublishedCodeGeneration publishStreaming(
            CodeGenerationBuildRequest scope,
            FileProducer producer
    ) {
        if (!scope.files().isEmpty()) {
            throw new IllegalArgumentException("streaming generation scope must not contain files");
        }
        Path building = child(scope.generationId() + ".building");
        Path published = child(scope.generationId().toString());
        try {
            Files.createDirectories(indexRoot);
            if (Files.exists(building) || Files.exists(published)) {
                throw new CodeGenerationValidationException("generation directory already exists");
            }
            GenerationIndexWriteSummary written = writer.writeStreaming(building, scope, producer);
            var validationFiles = written.paths().stream()
                    .map(path -> new CodeGenerationFile(path, "text", ""))
                    .toList();
            validator.validate(building, new CodeGenerationBuildRequest(
                    scope.generationId(), scope.projectId(), scope.branchId(), scope.snapshotId(),
                    scope.commit(), validationFiles));
            mover.move(building, published);
            return new PublishedCodeGeneration(scope.generationId(), written.documentCount());
        } catch (CodeGenerationValidationException failure) {
            cleanupFailure(building, published, failure);
            throw failure;
        } catch (IOException failure) {
            CodeGenerationPublishException wrapped = new CodeGenerationPublishException(failure);
            cleanupFailure(building, published, wrapped);
            throw wrapped;
        } catch (RuntimeException failure) {
            // 流式 producer 的归档读取或文件选择失败时同样不得留下可误用的候选目录。
            cleanupFailure(building, published, failure);
            throw failure;
        }
    }

    private Path child(String name) {
        Path child = indexRoot.resolve(name).normalize();
        if (!indexRoot.equals(child.getParent())) {
            throw new IllegalArgumentException("generation path escaped index root");
        }
        return child;
    }

    private void cleanupFailure(Path building, Path published, RuntimeException failure) {
        try {
            deleteTree(building);
            deleteTree(published);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void deleteTree(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        // target 已由服务端 generation Long 且直属 indexRoot 推导，绝不接受客户端或数据库物理路径。
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
