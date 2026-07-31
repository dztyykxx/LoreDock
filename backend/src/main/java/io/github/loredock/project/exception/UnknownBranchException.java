package io.github.loredock.project.exception;

/**
 * 显式选择的分支不属于当前项目；调用方必须失败，不能回退默认分支。
 */
public class UnknownBranchException extends RuntimeException {

    /** 创建不包含候选分支信息的安全异常。 */
    public UnknownBranchException() {
        super("branch not found");
    }
}
