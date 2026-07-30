package io.github.loredock.code.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 起始行超过活动 StoredField 文件的实际末尾。 */
public class CodeSnippetRangeInvalidException extends ApplicationException {
    /** 创建稳定 416 且不返回完整正文。 */
    public CodeSnippetRangeInvalidException() {
        super(ErrorCode.CODE_SNIPPET_RANGE_INVALID, "code snippet start line exceeds file");
    }
}
