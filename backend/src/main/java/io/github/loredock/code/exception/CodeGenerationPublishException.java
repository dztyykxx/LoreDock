package io.github.loredock.code.exception;

/** generation 写入、重新打开或原子发布出现基础设施失败。 */
public class CodeGenerationPublishException extends RuntimeException {
    /** 创建保留原因链但不在消息中记录物理路径的发布失败。 */
    public CodeGenerationPublishException(Throwable cause) {
        super("code generation publication failed", cause);
    }
}
