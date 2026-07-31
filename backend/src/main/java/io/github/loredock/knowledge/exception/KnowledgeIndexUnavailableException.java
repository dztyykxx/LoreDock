package io.github.loredock.knowledge.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 没有完整活动知识搜索 generation 或索引读取失败。 */
public final class KnowledgeIndexUnavailableException extends ApplicationException {

    /** 创建不暴露内部索引信息的 503 异常。 */
    public KnowledgeIndexUnavailableException() {
        super(ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE, "active knowledge search generation unavailable");
    }

    /**
     * @param cause 仅供结构化日志和诊断保留的内部原因
     */
    public KnowledgeIndexUnavailableException(Throwable cause) {
        super(ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE, "active knowledge search generation unavailable", cause);
    }
}
