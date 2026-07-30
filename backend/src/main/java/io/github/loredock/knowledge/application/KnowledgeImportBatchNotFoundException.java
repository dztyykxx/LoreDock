package io.github.loredock.knowledge.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 管理员请求的历史导入批次不存在。 */
public class KnowledgeImportBatchNotFoundException extends ApplicationException {

    /** 创建不泄露对象元数据的 404 失败。 */
    public KnowledgeImportBatchNotFoundException() {
        super(ErrorCode.DOCUMENT_IMPORT_BATCH_NOT_FOUND, "knowledge import batch not found");
    }
}
