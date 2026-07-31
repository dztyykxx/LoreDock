package io.github.loredock.code.exception;

/** 已写 generation 的文档数、唯一路径或身份元数据与预期业务范围不一致。 */
public class CodeGenerationValidationException extends RuntimeException {
    /** 创建不携带正文和物理路径的验证失败。 */
    public CodeGenerationValidationException(String reason) {
        super(reason);
    }
}
