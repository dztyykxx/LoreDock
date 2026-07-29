package io.github.loredock.storage.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.storage.application.StoredObjectRepository;
import io.github.loredock.storage.domain.StoredObject;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 MyBatis-Plus Java API 持久化对象元数据，不持有文件流或跨文件系统事务。
 */
@Repository
public class MybatisPlusStoredObjectRepository implements StoredObjectRepository {

    private static final String AVAILABLE = "AVAILABLE";
    private static final String DELETING = "DELETING";

    private final StoredObjectMapper mapper;

    /**
     * @param mapper 对象元数据 Mapper
     */
    public MybatisPlusStoredObjectRepository(StoredObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(StoredObject object, AuditMetadata audit) {
        mapper.insert(StoredObjectEntity.builder()
                .id(UUID.fromString(object.objectKey()))
                .objectKey(object.objectKey())
                .status(AVAILABLE)
                .originalFilename(object.originalFilename())
                .contentType(object.contentType())
                .sizeBytes(object.sizeBytes())
                .sha256(object.sha256())
                .createdAt(audit.createdAt())
                .updatedAt(audit.updatedAt())
                .createdBy(audit.createdBy())
                .updatedBy(audit.updatedBy())
                .build());
    }

    @Override
    public Optional<StoredObject> findAvailable(String objectKey) {
        StoredObjectEntity entity = mapper.selectOne(Wrappers.<StoredObjectEntity>lambdaQuery()
                .eq(StoredObjectEntity::getObjectKey, objectKey)
                .eq(StoredObjectEntity::getStatus, AVAILABLE));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public boolean markDeleting(String objectKey, Instant updatedAt, String updatedBy) {
        int updated = mapper.update(Wrappers.<StoredObjectEntity>lambdaUpdate()
                .eq(StoredObjectEntity::getObjectKey, objectKey)
                .in(StoredObjectEntity::getStatus, AVAILABLE, DELETING)
                .set(StoredObjectEntity::getStatus, DELETING)
                .set(StoredObjectEntity::getUpdatedAt, updatedAt)
                .set(StoredObjectEntity::getUpdatedBy, updatedBy));
        return updated > 0;
    }

    @Override
    public void delete(String objectKey) {
        mapper.delete(Wrappers.<StoredObjectEntity>lambdaQuery()
                .eq(StoredObjectEntity::getObjectKey, objectKey));
    }

    private StoredObject toDomain(StoredObjectEntity entity) {
        return new StoredObject(
                entity.getObjectKey(),
                entity.getOriginalFilename(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getCreatedAt()
        );
    }
}
