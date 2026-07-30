package io.github.loredock.knowledge.infrastructure.importing;

/** 单个可处理文件不是严格 UTF-8；应用层应把它记录为失败条目而不是猜测编码。 */
public class InvalidImportTextEncodingException extends RuntimeException {

    /** 创建不包含正文或原始解码异常消息的安全失败。 */
    public InvalidImportTextEncodingException() {
        super("import text is not valid UTF-8");
    }
}
