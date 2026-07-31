package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.enums.KnowledgeImportFileKind;

/**
 * 已执行上传字节上限与外层类型检查的内存载荷；MVP 上限默认 20 MiB。
 *
 * @param originalFilename 不可信原文件名
 * @param kind 已验证外层类型
 * @param bytes 原始上传字节的防御性副本
 */
public record KnowledgeImportPayload(String originalFilename, KnowledgeImportFileKind kind, byte[] bytes) {
    public KnowledgeImportPayload {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
