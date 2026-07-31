package io.github.loredock.project.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 分支名违反项目内唯一约束。 */
public class BranchNameConflictException extends ApplicationException {
    /** 创建稳定冲突失败，不携带数据库异常正文。 */
    public BranchNameConflictException() { super(ErrorCode.BRANCH_NAME_CONFLICT, "branch name conflict"); }

    /** @param cause 唯一约束原始失败，仅用于服务端诊断 */
    public BranchNameConflictException(Throwable cause) {
        super(ErrorCode.BRANCH_NAME_CONFLICT, "branch name conflict", cause);
    }
}
