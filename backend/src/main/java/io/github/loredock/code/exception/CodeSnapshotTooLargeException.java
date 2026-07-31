package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 上传真实字节或归档资源声明超过服务端硬上限。 */
public class CodeSnapshotTooLargeException extends ApplicationException {

    /** 创建不回显文件名、条目名或配置物理路径的 413 失败。 */
    public CodeSnapshotTooLargeException() {
        super(ErrorCode.CODE_SNAPSHOT_TOO_LARGE, "code snapshot exceeds configured resource limit");
    }
}
