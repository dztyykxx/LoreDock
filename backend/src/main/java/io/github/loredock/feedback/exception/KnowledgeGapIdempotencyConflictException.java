package io.github.loredock.feedback.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 同一操作者复用幂等键提交不同输入时使用的稳定 409。 */
public class KnowledgeGapIdempotencyConflictException extends ApplicationException {
    public KnowledgeGapIdempotencyConflictException() {
        super(ErrorCode.KNOWLEDGE_GAP_IDEMPOTENCY_CONFLICT, "KNOWLEDGE_GAP_IDEMPOTENCY_CONFLICT");
    }
}
