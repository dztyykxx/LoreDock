package io.github.loredock.knowledge.infrastructure.importing;

import io.github.loredock.knowledge.application.ImportObjectCompensation;
import io.github.loredock.storage.application.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 对未建立批次引用的上传对象执行幂等删除；补偿日志只记录追踪语义，不包含对象键或正文。 */
@Component
public class ObjectStorageImportCompensation implements ImportObjectCompensation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageImportCompensation.class);
    private final ObjectStorage objectStorage;

    /** @param objectStorage 原始上传对象存储 */
    public ObjectStorageImportCompensation(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public void deleteUnreferenced(String objectKey) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException exception) {
            // 删除适配器异常可能携带对象键、路径或文件元数据；安全日志只发出可监控事件，不渲染异常链。
            LOGGER.error("knowledge_import_unreferenced_object_compensation_failed");
        }
    }
}
