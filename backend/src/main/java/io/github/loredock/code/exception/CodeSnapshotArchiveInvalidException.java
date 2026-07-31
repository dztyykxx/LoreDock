package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** ZIP 中央目录、条目类型、路径或完整读取结果不安全。 */
public class CodeSnapshotArchiveInvalidException extends ApplicationException {

    /** 创建不暴露条目名、对象键和临时路径的 422 失败。 */
    public CodeSnapshotArchiveInvalidException() {
        super(ErrorCode.CODE_SNAPSHOT_ARCHIVE_INVALID, "code snapshot archive invalid");
    }

    /** @param cause 仅供服务端诊断的归档解析失败链 */
    public CodeSnapshotArchiveInvalidException(Throwable cause) {
        super(ErrorCode.CODE_SNAPSHOT_ARCHIVE_INVALID, "code snapshot archive invalid", cause);
    }
}
