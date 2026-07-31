package io.github.loredock.project.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 项目标识违反全局唯一约束。 */
public class ProjectIdentifierConflictException extends ApplicationException {
    /** 创建稳定冲突失败，不携带数据库异常正文。 */
    public ProjectIdentifierConflictException() { super(ErrorCode.PROJECT_IDENTIFIER_CONFLICT, "project identifier conflict"); }

    /** @param cause 唯一约束原始失败，仅用于服务端诊断 */
    public ProjectIdentifierConflictException(Throwable cause) {
        super(ErrorCode.PROJECT_IDENTIFIER_CONFLICT, "project identifier conflict", cause);
    }
}
