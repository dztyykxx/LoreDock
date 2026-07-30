package io.github.loredock.knowledgegap.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 跳过、倒退或竞态后不再满足状态前置条件时使用的稳定 409。 */
public class KnowledgeGapStatusConflictException extends ApplicationException {
    public KnowledgeGapStatusConflictException() {
        super(ErrorCode.KNOWLEDGE_GAP_STATUS_CONFLICT, "KNOWLEDGE_GAP_STATUS_CONFLICT");
    }
}
