package io.github.loredock.knowledge.application.search;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 活动 generation 要求的离线查询 Embedding 模型不可用或不匹配。 */
public final class KnowledgeEmbeddingUnavailableException extends ApplicationException {

    /** 创建不暴露模型路径或内部配置的 503 异常。 */
    public KnowledgeEmbeddingUnavailableException() {
        super(ErrorCode.KNOWLEDGE_EMBEDDING_UNAVAILABLE, "knowledge embedding model unavailable");
    }

    /**
     * @param cause 仅供结构化日志和诊断保留的内部原因
     */
    public KnowledgeEmbeddingUnavailableException(Throwable cause) {
        super(ErrorCode.KNOWLEDGE_EMBEDDING_UNAVAILABLE, "knowledge embedding model unavailable", cause);
    }
}
