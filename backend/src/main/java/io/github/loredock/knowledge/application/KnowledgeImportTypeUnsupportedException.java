package io.github.loredock.knowledge.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 外层上传扩展名不属于 Markdown、文本或 ZIP。 */
public class KnowledgeImportTypeUnsupportedException extends ApplicationException {

    /** 创建不回显不可信文件名的 415 失败。 */
    public KnowledgeImportTypeUnsupportedException() {
        super(ErrorCode.DOCUMENT_IMPORT_TYPE_UNSUPPORTED, "knowledge import type unsupported");
    }
}
