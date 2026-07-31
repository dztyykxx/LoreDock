package io.github.loredock.project.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 指定分支不属于当前项目，调用方不得回退到 main 或其他分支。 */
public class BranchNotFoundException extends ApplicationException {
    /** 创建不泄露其他项目分支的失败。 */
    public BranchNotFoundException() { super(ErrorCode.BRANCH_NOT_FOUND, "branch not found"); }
}
