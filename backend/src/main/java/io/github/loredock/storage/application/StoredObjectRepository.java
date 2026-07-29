package io.github.loredock.storage.application;

import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.storage.domain.StoredObject;

import java.time.Instant;
import java.util.Optional;

/**
 * 对象元数据持久化边界，使文件适配器不直接拼接 SQL，并以数据库记录决定对象是否可读。
 */
public interface StoredObjectRepository {

    /**
     * 保存已原子发布文件的可用元数据。
     *
     * @param object 对象描述
     * @param audit 创建审计值
     */
    void save(StoredObject object, AuditMetadata audit);

    /**
     * @param objectKey 对象键
     * @return 状态为 AVAILABLE 的对象描述
     */
    Optional<StoredObject> findAvailable(String objectKey);

    /**
     * 将对象标记为删除中；已处于删除中也视为可继续删除。
     *
     * @param objectKey 对象键
     * @param updatedAt 更新 UTC 时刻
     * @param updatedBy 更新操作者
     * @return 元数据存在时为 true
     */
    boolean markDeleting(String objectKey, Instant updatedAt, String updatedBy);

    /**
     * 删除对象元数据，重复调用保持幂等。
     *
     * @param objectKey 对象键
     */
    void delete(String objectKey);
}
