package io.github.loredock.qa.api;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 会话不存在，或不属于当前操作者和 URL 项目；统一 404 防止范围枚举。 */
public class QaConversationNotFoundException extends ApplicationException {
    /** 创建不泄露实际存在性的会话不可见错误语义。 */
    public QaConversationNotFoundException() {
        super(ErrorCode.QA_CONVERSATION_NOT_FOUND, "QA_CONVERSATION_NOT_FOUND");
    }
}
