package io.github.loredock.code.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 管理端指定的代码快照 UUID 不存在。 */
public class CodeSnapshotNotFoundException extends ApplicationException {
    /** 创建不泄漏对象键和历史范围的 404。 */
    public CodeSnapshotNotFoundException() {
        super(ErrorCode.CODE_SNAPSHOT_NOT_FOUND, "code snapshot not found");
    }
}
