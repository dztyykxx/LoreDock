package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 未知任务、非代码任务或缺失快照范围统一按代码任务不存在处理。 */
public class CodeSnapshotJobNotFoundException extends ApplicationException {

    /** 创建不泄漏其他后台任务类型和输入信息的 404。 */
    public CodeSnapshotJobNotFoundException() {
        super(ErrorCode.CODE_SNAPSHOT_JOB_NOT_FOUND, "code snapshot job not found");
    }
}
