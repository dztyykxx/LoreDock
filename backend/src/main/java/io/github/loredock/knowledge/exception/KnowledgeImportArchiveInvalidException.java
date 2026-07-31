package io.github.loredock.knowledge.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** ZIP 结构、签名、资源上限或中央目录不满足安全边界。 */
public class KnowledgeImportArchiveInvalidException extends ApplicationException {

    /** 创建不携带解析器错误、对象键或临时路径的 422 失败。 */
    public KnowledgeImportArchiveInvalidException() {
        super(ErrorCode.DOCUMENT_IMPORT_ARCHIVE_INVALID, "knowledge import archive invalid");
    }

    /**
     * @param cause 仅供服务端脱敏诊断的原始失败
     */
    public KnowledgeImportArchiveInvalidException(Throwable cause) {
        super(ErrorCode.DOCUMENT_IMPORT_ARCHIVE_INVALID, "knowledge import archive invalid", cause);
    }
}
