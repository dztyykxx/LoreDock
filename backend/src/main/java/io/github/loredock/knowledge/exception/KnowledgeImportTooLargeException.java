package io.github.loredock.knowledge.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 上传流超过强类型配置的最大字节数。 */
public class KnowledgeImportTooLargeException extends ApplicationException {

    /** 创建不包含实际文件名或正文的 413 失败。 */
    public KnowledgeImportTooLargeException() {
        super(ErrorCode.DOCUMENT_IMPORT_TOO_LARGE, "knowledge import exceeds upload limit");
    }
}
