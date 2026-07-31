package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 代码快照外层扩展名、MIME 或 ZIP 魔数不一致。 */
public class CodeSnapshotTypeUnsupportedException extends ApplicationException {

    /** 创建不回显不可信文件名和请求正文的 415 失败。 */
    public CodeSnapshotTypeUnsupportedException() {
        super(ErrorCode.CODE_SNAPSHOT_TYPE_UNSUPPORTED, "code snapshot outer type unsupported");
    }
}
