package io.github.loredock.platform.web;

import org.springframework.http.HttpStatus;

/**
 * 平台稳定错误码及其 HTTP 语义；新增业务错误应显式登记，不能直接把异常消息暴露给客户端。
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    AUTH_LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "请先登录"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "当前身份无权执行此操作"),
    MCP_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "MCP Token 无效"),
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "项目不存在"),
    BRANCH_NOT_FOUND(HttpStatus.NOT_FOUND, "分支不存在"),
    PROJECT_IDENTIFIER_CONFLICT(HttpStatus.CONFLICT, "项目标识已存在"),
    BRANCH_NAME_CONFLICT(HttpStatus.CONFLICT, "项目分支已存在"),
    OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "对象不存在"),
    INVALID_OBJECT_KEY(HttpStatus.BAD_REQUEST, "对象键不合法"),
    STORAGE_WRITE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "对象写入失败"),
    UNSUPPORTED_JOB_TYPE(HttpStatus.BAD_REQUEST, "任务类型不受支持"),
    JOB_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "后台任务容量不足"),
    INVALID_JOB_TRANSITION(HttpStatus.CONFLICT, "任务状态转换不合法"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用");

    private final HttpStatus status;
    private final String publicMessage;

    ErrorCode(HttpStatus status, String publicMessage) {
        this.status = status;
        this.publicMessage = publicMessage;
    }

    /**
     * @return 对应 HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * @return 不含内部实现信息的默认消息
     */
    public String publicMessage() {
        return publicMessage;
    }
}
