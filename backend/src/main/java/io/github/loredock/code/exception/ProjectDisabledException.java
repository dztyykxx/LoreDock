package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 管理员尝试为已停用项目创建代码快照。 */
public class ProjectDisabledException extends ApplicationException {

    /** 创建不暴露项目其他内部状态的稳定 409 失败。 */
    public ProjectDisabledException() {
        super(ErrorCode.PROJECT_DISABLED, "disabled project cannot accept code snapshot");
    }
}
