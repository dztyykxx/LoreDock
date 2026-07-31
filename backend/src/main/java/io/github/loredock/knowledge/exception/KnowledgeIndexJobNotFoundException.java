package io.github.loredock.knowledge.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 任务不存在或不是知识重建类型时统一使用的 404，避免泄露其他后台任务。 */
public class KnowledgeIndexJobNotFoundException extends ApplicationException {

    /** 创建脱敏知识任务不存在错误。 */
    public KnowledgeIndexJobNotFoundException() {
        super(ErrorCode.DOCUMENT_INDEX_JOB_NOT_FOUND, "knowledge index job not found");
    }
}
