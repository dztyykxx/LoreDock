package io.github.loredock.qa.api;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 同一会话已有 ACCEPTED 或 RUNNING 轮次，拒绝产生分叉上下文。 */
public class QaConversationBusyException extends ApplicationException {
    /** 创建稳定的会话忙错误语义。 */
    public QaConversationBusyException() {
        super(ErrorCode.QA_CONVERSATION_BUSY, "QA_CONVERSATION_BUSY");
    }
}
