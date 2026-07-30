package io.github.loredock.code.application;

import java.io.InputStream;

/**
 * 尚未持久化的 ZIP 请求正文。输入流由调用方关闭，服务端生成对象键和工作路径，原始文件名只作元数据。
 *
 * @param input 不可信上传流
 * @param originalFilename 可选原始文件名，仅用于类型判断和审计
 * @param contentType 客户端声明 MIME
 * @param declaredSize 客户端或 multipart 层已知大小，未知时为 -1
 */
public record CodeSnapshotUpload(
        InputStream input,
        String originalFilename,
        String contentType,
        long declaredSize
) {
}
