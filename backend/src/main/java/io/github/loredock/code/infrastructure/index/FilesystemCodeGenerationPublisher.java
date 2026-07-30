package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeGenerationBuildRequest;
import io.github.loredock.code.application.CodeGenerationFile;
import io.github.loredock.code.application.CodeGenerationFileProducer;
import io.github.loredock.code.application.CodeGenerationPublishPort;
import io.github.loredock.code.application.PublishedCodeGeneration;
import io.github.loredock.code.infrastructure.CodeSnapshotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 基于同一索引根原子重命名的 generation 发布器。任何失败都会清理本次 UUID 对应的构建和未引用最终目录。
 */
@Component
public class FilesystemCodeGenerationPublisher implements CodeGenerationPublishPort {

    private final Path indexRoot;
    private final GenerationIndexWriter writer;
    private final GenerationIndexValidator validator;
    private final GenerationDirectoryMover mover;

    /** @param properties 已验证且与对象根不重叠的代码索引配置 */
    @Autowired
    public FilesystemCodeGenerationPublisher(CodeSnapshotProperties properties) {
        this(properties.indexRoot(), new LuceneGenerationWriter(),
                new LuceneGenerationValidator(), GenerationDirectoryMover.atomic());
    }

    FilesystemCodeGenerationPublisher(
            Path indexRoot,
            GenerationIndexWriter writer,
            GenerationIndexValidator validator,
            GenerationDirectoryMover mover
    ) {
        this.indexRoot = indexRoot.toAbsolutePath().normalize();
        this.writer = writer;
        this.validator = validator;
        this.mover = mover;
    }

    @Override
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

    @Override
    public PublishedCodeGeneration publishStreaming(
            CodeGenerationBuildRequest scope,
            CodeGenerationFileProducer producer
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
        // target 已由服务端 generation UUID 且直属 indexRoot 推导，绝不接受客户端或数据库物理路径。
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
