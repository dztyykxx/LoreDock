package io.github.loredock.knowledgegap.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 管理查询不存在反馈时使用的稳定 404。 */
public class KnowledgeGapNotFoundException extends ApplicationException {
    public KnowledgeGapNotFoundException() {
        super(ErrorCode.KNOWLEDGE_GAP_NOT_FOUND, "KNOWLEDGE_GAP_NOT_FOUND");
    }
}
