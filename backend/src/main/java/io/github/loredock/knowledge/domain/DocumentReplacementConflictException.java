package io.github.loredock.knowledge.domain;

/** 替代关系违反同范围、发布态、无环或唯一当前替代者规则；HTTP 边界映射为替代冲突。 */
public class DocumentReplacementConflictException extends RuntimeException {

    /** 创建不暴露新旧文档内容的替代冲突。 */
    public DocumentReplacementConflictException() {
        super("document replacement conflict");
    }
}
