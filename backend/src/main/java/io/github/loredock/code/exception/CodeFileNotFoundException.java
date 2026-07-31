package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 活动索引中不存在指定允许文件；敏感、忽略和跨范围路径使用同一语义。 */
public class CodeFileNotFoundException extends ApplicationException {
    /** 创建不泄漏忽略原因和其他范围存在性的 404。 */
    public CodeFileNotFoundException() {
        super(ErrorCode.CODE_FILE_NOT_FOUND, "active code file not found");
    }
}
