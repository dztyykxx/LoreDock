package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 活动快照原始 ZIP 对象已不可用，重建不得从其他来源回退。 */
public class CodeSnapshotObjectNotFoundException extends ApplicationException {
    /** 创建不暴露对象键的稳定 404。 */
    public CodeSnapshotObjectNotFoundException() {
        super(ErrorCode.OBJECT_NOT_FOUND, "active code snapshot object is unavailable");
    }
}
