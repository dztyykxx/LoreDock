package io.github.loredock.code.model.result;

/**
 * 已完成全包结构校验的普通 ZIP 文件描述。
 *
 * @param path 规范仓库相对路径
 * @param compressedSize 中央目录压缩字节数
 * @param uncompressedSize 中央目录声明展开字节数
 */
public record CodeArchiveEntry(String path, long compressedSize, long uncompressedSize) {
}
