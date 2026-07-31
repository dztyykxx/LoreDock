package io.github.loredock.code.model.result;

import java.io.InputStream;

/**
 * 已通过同步外层类型检查、仍按真实读取字节受限的上传流。
 *
 * @param input 由对象存储流式读取、调用方关闭的输入
 * @param contentType 规范 MIME
 */
public record ValidatedCodeSnapshotUpload(InputStream input, String contentType) {
}
