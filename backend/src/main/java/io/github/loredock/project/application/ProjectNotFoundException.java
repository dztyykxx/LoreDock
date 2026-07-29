package io.github.loredock.project.application;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 项目不存在；普通入口对停用项目也使用此语义以防枚举。 */
public class ProjectNotFoundException extends ApplicationException {
    /** 创建不包含项目内部数据的失败。 */
    public ProjectNotFoundException() { super(ErrorCode.PROJECT_NOT_FOUND, "project not found"); }
}
