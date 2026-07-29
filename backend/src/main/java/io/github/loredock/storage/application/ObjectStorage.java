package io.github.loredock.storage.application;

import io.github.loredock.storage.domain.ObjectMetadata;
import io.github.loredock.storage.domain.StoredObject;

import java.io.InputStream;

/**
 * 业务层使用的对象存储端口，隐藏本地文件布局并为后续 S3 适配保留稳定边界。
 */
public interface ObjectStorage {

    /**
     * 流式写入并原子发布对象；失败时对象不可见且不留下本次临时文件。
     *
     * @param input 对象输入流，调用方负责关闭
     * @param metadata 原始文件描述
     * @return 包含对象键和校验信息的对象描述
     */
    StoredObject put(InputStream input, ObjectMetadata metadata);

    /**
     * 打开已发布对象的读取流。
     *
     * @param objectKey 对象键
     * @return 由调用方关闭的输入流
     */
    InputStream get(String objectKey);

    /**
     * 幂等检查对象是否可读取。
     *
     * @param objectKey 对象键
     * @return 数据库元数据和本地文件均可用时为 true
     */
    boolean exists(String objectKey);

    /**
     * 幂等删除对象；对象不存在时也视为成功。
     *
     * @param objectKey 对象键
     */
    void delete(String objectKey);
}
