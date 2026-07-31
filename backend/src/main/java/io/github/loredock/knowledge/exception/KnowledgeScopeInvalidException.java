package io.github.loredock.knowledge.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 管理写入的知识范围字段缺失、残留或不匹配项目主数据。 */
public class KnowledgeScopeInvalidException extends ApplicationException {

    /** 创建不暴露其他项目或分支信息的稳定范围失败。 */
    public KnowledgeScopeInvalidException() {
        super(ErrorCode.DOCUMENT_SCOPE_INVALID, "knowledge scope invalid");
    }
}
