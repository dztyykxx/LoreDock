package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeSnapshotRecoveryRepository;
import io.github.loredock.code.infrastructure.CodeSnapshotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** 后台任务恢复完成后协调代码候选状态，并清理构建目录和无活动数据库引用的孤儿。 */
@Component
public class CodeSnapshotRecoveryCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodeSnapshotRecoveryCoordinator.class);
    private final CodeSnapshotRecoveryRepository repository;
    private final Path indexRoot;
    private final GenerationDirectoryCleaner cleaner;

    /** 生产构造器。 */
    @Autowired
    public CodeSnapshotRecoveryCoordinator(
            CodeSnapshotRecoveryRepository repository,
            CodeSnapshotProperties properties
    ) {
        this(repository, properties.indexRoot(), GenerationDirectoryCleaner.recursive());
    }

    /** 创建使用显式索引根的恢复协调器，供同包验证与受控启动集成使用。 */
    public CodeSnapshotRecoveryCoordinator(CodeSnapshotRecoveryRepository repository, Path indexRoot) {
        this(repository, indexRoot, GenerationDirectoryCleaner.recursive());
    }

    CodeSnapshotRecoveryCoordinator(
            CodeSnapshotRecoveryRepository repository,
            Path indexRoot,
            GenerationDirectoryCleaner cleaner
    ) {
        this.repository = repository;
        this.indexRoot = indexRoot.toAbsolutePath().normalize();
        this.cleaner = cleaner;
    }

    /** 执行一次幂等恢复；单个清理失败只记录 UUID 和错误类别，不阻断活动索引。 */
    public void recover() {
        Set<UUID> active = repository.reconcileInterruptedBuilds();
        if (!Files.isDirectory(indexRoot)) {
            return;
        }
        try (var children = Files.list(indexRoot)) {
            children.forEach(path -> cleanIfUnreferenced(path, active));
        } catch (IOException failure) {
            LOGGER.warn("code_index_recovery_scan_failed category=io");
        }
    }

    /** 确保先执行通用后台任务陈旧心跳恢复，再判断 BUILDING generation 的任务终态。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void onApplicationReady() {
        recover();
    }

    private void cleanIfUnreferenced(Path path, Set<UUID> active) {
        String name = path.getFileName().toString();
        UUID generationId = parseGeneration(name.endsWith(".building")
                ? name.substring(0, name.length() - ".building".length()) : name);
        if (generationId == null || !name.endsWith(".building") && active.contains(generationId)) {
            return;
        }
        try {
            cleaner.delete(path);
        } catch (IOException failure) {
            LOGGER.warn("code_index_recovery_cleanup_failed generationId={} category=io", generationId);
        }
    }

    private UUID parseGeneration(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }
}
