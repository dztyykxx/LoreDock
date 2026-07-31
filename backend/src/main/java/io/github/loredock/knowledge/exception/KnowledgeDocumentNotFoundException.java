package io.github.loredock.knowledge.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 文档不存在或对当前查询上下文不可见，统一使用不泄露存在性的失败语义。 */
public class KnowledgeDocumentNotFoundException extends ApplicationException {

    /** 创建不包含文档正文、范围或内部标识的安全失败。 */
    public KnowledgeDocumentNotFoundException() {
        super(ErrorCode.DOCUMENT_NOT_FOUND, "knowledge document not found");
    }
}
