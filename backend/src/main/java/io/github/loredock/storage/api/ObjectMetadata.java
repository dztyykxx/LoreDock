package io.github.loredock.storage.api;

import java.util.Objects;

/**
 * 调用方提供的对象描述；原始文件名仅作为元数据，不参与磁盘路径计算。
 *
 * @param originalFilename 原始文件名
 * @param contentType MIME 类型
 */
public record ObjectMetadata(String originalFilename, String contentType) {

    /**
     * 校验对象描述中后续调用方依赖的必填字段。
     */
    public ObjectMetadata {
        Objects.requireNonNull(originalFilename, "原始文件名不能为空");
        Objects.requireNonNull(contentType, "MIME 类型不能为空");
        if (originalFilename.isBlank() || originalFilename.length() > 512) {
            throw new IllegalArgumentException("原始文件名长度必须在 1 到 512 之间");
        }
        if (contentType.isBlank() || contentType.length() > 255) {
            throw new IllegalArgumentException("MIME 类型长度必须在 1 到 255 之间");
        }
    }
}
