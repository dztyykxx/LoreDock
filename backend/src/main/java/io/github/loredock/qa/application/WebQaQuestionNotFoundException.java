package io.github.loredock.qa.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 问答不存在、操作者不匹配、项目不匹配或项目停用时统一返回的安全错误。 */
public class WebQaQuestionNotFoundException extends ApplicationException {
    /** 创建不携带真实归属信息的失败。 */
    public WebQaQuestionNotFoundException() {
        super(ErrorCode.QA_QUESTION_NOT_FOUND, "QA_QUESTION_NOT_FOUND");
    }
}
