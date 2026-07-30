package io.github.loredock.code.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 管理员尝试重建候选、失败或已替换快照。 */
public class CodeSnapshotNotActiveException extends ApplicationException {
    /** 创建不提供历史查询入口的 409。 */
    public CodeSnapshotNotActiveException() {
        super(ErrorCode.CODE_SNAPSHOT_NOT_ACTIVE, "code snapshot is not active");
    }
}
