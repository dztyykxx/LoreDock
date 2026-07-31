package io.github.loredock.storage.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.storage.config.StorageProperties;
import io.github.loredock.storage.mapper.StoredObjectMapper;
import io.github.loredock.storage.model.entity.StoredObjectEntity;
import io.github.loredock.storage.model.result.ObjectMetadata;
import io.github.loredock.storage.model.result.StoredObject;
import io.github.loredock.storage.service.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 本地持久化对象存储实现：流式计算校验值、原子发布文件，再登记数据库元数据。
 * 文件系统和数据库无法组成同一事务，因此数据库失败时显式补偿删除已发布文件。
 */
@Component
public class LocalFileObjectStorage implements ObjectStorage {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String AVAILABLE = "AVAILABLE";
    private static final String DELETING = "DELETING";
    private final SafeObjectPathResolver pathResolver;
    private final StoredObjectMapper objects;
    private final AuditMetadataFactory auditFactory;
    private final Clock timeProvider;
    private final Supplier<String> actorProvider;

    /**
     * 创建本地适配器。
     *
     * @param properties 持久化根目录配置
     * @param objects 对象元数据 Mapper
     * @param auditFactory 审计值工厂
     * @param timeProvider UTC 时间端口
     * @param actorProvider 当前操作者端口
     */
    public LocalFileObjectStorage(
            StorageProperties properties,
            StoredObjectMapper objects,
            AuditMetadataFactory auditFactory,
            Clock timeProvider,
            @Qualifier("auditActorSupplier") Supplier<String> actorProvider
    ) {
        this.pathResolver = new SafeObjectPathResolver(properties.root());
        this.objects = objects;
        this.auditFactory = auditFactory;
        this.timeProvider = timeProvider;
        this.actorProvider = actorProvider;
    }

    @Override
    public StoredObject put(InputStream input, ObjectMetadata metadata) {
        Objects.requireNonNull(input, "对象输入流不能为空");
        Objects.requireNonNull(metadata, "对象元数据不能为空");
        String objectKey = java.util.UUID.randomUUID().toString();
        Path target = pathResolver.resolve(objectKey);
        Path temporary = null;
        boolean published = false;
        try {
            pathResolver.ensureParent(target);
            temporary = Files.createTempFile(pathResolver.root(), ".upload-", ".tmp");
            WriteResult result = copyAndDigest(input, temporary);
            publishAtomically(temporary, target);
            published = true;
            AuditMetadata audit = auditFactory.created();
            StoredObject stored = new StoredObject(
                    objectKey,
                    metadata.originalFilename(),
                    metadata.contentType(),
                    result.sizeBytes(),
                    result.sha256(),
                    audit.createdAt()
            );
            save(stored, audit);
            return stored;
        } catch (ApplicationException exception) {
            compensate(temporary, target, published);
            throw exception;
        } catch (Exception exception) {
            compensate(temporary, target, published);
            throw new ApplicationException(
                    ErrorCode.STORAGE_WRITE_FAILED,
                    "对象写入或元数据登记失败",
                    exception
            );
        }
    }

    @Override
    public InputStream get(String objectKey) {
        Path path = pathResolver.resolve(objectKey);
        findAvailable(objectKey).orElseThrow(() ->
                new ApplicationException(ErrorCode.OBJECT_NOT_FOUND, "对象元数据不存在或不可用"));
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path)) {
                throw new ApplicationException(ErrorCode.INVALID_OBJECT_KEY, "对象文件被替换为符号链接");
            }
            throw new ApplicationException(ErrorCode.OBJECT_NOT_FOUND, "对象文件不存在");
        }
        try {
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new ApplicationException(ErrorCode.OBJECT_NOT_FOUND, "对象文件无法读取", exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        Path path = pathResolver.resolve(objectKey);
        return findAvailable(objectKey).isPresent()
                && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    @Override
    public void delete(String objectKey) {
        Path path = pathResolver.resolve(objectKey);
        if (!markDeleting(objectKey, timeProvider.instant(), actorProvider.get())) {
            return;
        }
        try {
            // deleteIfExists 删除符号链接本身而不会跟随目标，幂等删除因此仍然安全。
            Files.deleteIfExists(path);
            objects.delete(Wrappers.<StoredObjectEntity>lambdaQuery()
                    .eq(StoredObjectEntity::getObjectKey, objectKey));
        } catch (IOException exception) {
            throw new ApplicationException(ErrorCode.STORAGE_WRITE_FAILED, "对象文件删除失败", exception);
        }
    }

    private void save(StoredObject object, AuditMetadata audit) {
        objects.insert(StoredObjectEntity.builder()
                .objectKey(object.objectKey()).status(AVAILABLE)
                .originalFilename(object.originalFilename()).contentType(object.contentType())
                .sizeBytes(object.sizeBytes()).sha256(object.sha256())
                .createdAt(audit.createdAt()).updatedAt(audit.updatedAt())
                .createdBy(audit.createdBy()).updatedBy(audit.updatedBy()).build());
    }

    private Optional<StoredObject> findAvailable(String objectKey) {
        StoredObjectEntity entity = objects.selectOne(Wrappers.<StoredObjectEntity>lambdaQuery()
                .eq(StoredObjectEntity::getObjectKey, objectKey)
                .eq(StoredObjectEntity::getStatus, AVAILABLE));
        return Optional.ofNullable(entity).map(value -> new StoredObject(
                value.getObjectKey(), value.getOriginalFilename(), value.getContentType(), value.getSizeBytes(),
                value.getSha256(), value.getCreatedAt()));
    }

    private boolean markDeleting(String objectKey, java.time.Instant updatedAt, String updatedBy) {
        return objects.update(Wrappers.<StoredObjectEntity>lambdaUpdate()
                .eq(StoredObjectEntity::getObjectKey, objectKey)
                .in(StoredObjectEntity::getStatus, AVAILABLE, DELETING)
                .set(StoredObjectEntity::getStatus, DELETING)
                .set(StoredObjectEntity::getUpdatedAt, updatedAt)
                .set(StoredObjectEntity::getUpdatedBy, updatedBy)) > 0;
    }

    private WriteResult copyAndDigest(InputStream input, Path temporary) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(
                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return new WriteResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    private void publishAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("对象存储文件系统不支持原子移动", exception);
        }
    }

    private void compensate(Path temporary, Path target, boolean published) {
        try {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
            if (published) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
            // 补偿失败不能覆盖原始异常；孤儿文件不可被读取，并由后续受控清理处理。
        }
    }

    private record WriteResult(long sizeBytes, String sha256) {
    }
}
