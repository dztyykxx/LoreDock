package io.github.loredock.qa.application;

/** 问答不存在、操作者不匹配、项目不匹配或项目停用时统一返回的安全错误。 */
public class WebQaQuestionNotFoundException extends RuntimeException {
    public WebQaQuestionNotFoundException() {
        super("QA_QUESTION_NOT_FOUND");
    }
}
