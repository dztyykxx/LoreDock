package io.github.loredock.knowledge.exception;

/** 文档生命周期不允许当前操作；HTTP 边界映射为 DOCUMENT_STATE_CONFLICT。 */
public class DocumentStateConflictException extends RuntimeException {

    /** 创建不暴露正文或来源数据的状态冲突。 */
    public DocumentStateConflictException() {
        super("document state conflict");
    }
}
