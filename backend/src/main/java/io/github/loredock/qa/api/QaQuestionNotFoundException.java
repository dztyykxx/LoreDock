package io.github.loredock.qa.api;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 问答不存在或当前操作者、项目范围不可见。 */
public class QaQuestionNotFoundException extends ApplicationException {
    public QaQuestionNotFoundException() {
        super(ErrorCode.QA_QUESTION_NOT_FOUND, "QA_QUESTION_NOT_FOUND");
    }
}
