package io.github.loredock.code.service.storage;

import io.github.loredock.storage.api.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 未建立快照引用时幂等删除原始对象；日志不渲染对象键、物理路径或异常链。 */
@Component
public class ObjectStorageCodeSnapshotCompensation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageCodeSnapshotCompensation.class);
    private final ObjectStorage objectStorage;

    /** @param objectStorage 原始对象存储端口 */
    public ObjectStorageCodeSnapshotCompensation(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    public void deleteUnreferenced(String objectKey) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException failure) {
            // 补偿失败不能覆盖原事务语义；孤儿对象没有快照引用，业务查询无法读取，后续由运维审计清理。
            LOGGER.error("code_snapshot_unreferenced_object_compensation_failed");
        }
    }
}
