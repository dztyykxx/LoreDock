package io.github.loredock.knowledge.application;

import java.io.InputStream;

/**
 * HTTP multipart 边界交给导入用例的流式上传；文件名和 MIME 只是不可信提示，类型仍须检查扩展名与签名。
 *
 * @param originalFilename 原文件名元数据
 * @param contentType 客户端声明的 MIME
 * @param content 上传流，由用例负责在返回前完成读取，调用方负责关闭
 */
public record KnowledgeImportUpload(String originalFilename, String contentType, InputStream content) {
}
