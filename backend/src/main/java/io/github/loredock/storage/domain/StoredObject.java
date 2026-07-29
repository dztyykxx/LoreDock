package io.github.loredock.storage.domain;

import java.time.Instant;

/**
 * 已成功发布且可读取的对象描述。
 *
 * @param objectKey 服务生成的安全对象键
 * @param originalFilename 原始文件名元数据
 * @param contentType MIME 类型
 * @param sizeBytes 字节数
 * @param sha256 小写十六进制 SHA-256
 * @param createdAt 创建 UTC 时刻
 */
public record StoredObject(
        String objectKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant createdAt
) {
}
