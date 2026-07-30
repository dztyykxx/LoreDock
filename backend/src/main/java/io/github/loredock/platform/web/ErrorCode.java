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
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "知识文档不存在"),
    DOCUMENT_SCOPE_INVALID(HttpStatus.BAD_REQUEST, "知识文档范围不合法"),
    DOCUMENT_STATE_CONFLICT(HttpStatus.CONFLICT, "知识文档状态冲突"),
    DOCUMENT_REPLACEMENT_CONFLICT(HttpStatus.CONFLICT, "知识文档替代关系冲突"),
    DOCUMENT_IMPORT_TYPE_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持该导入文件类型"),
    DOCUMENT_IMPORT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "导入文件超过大小限制"),
    DOCUMENT_IMPORT_ARCHIVE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "ZIP 文件不安全或无法处理"),
    DOCUMENT_IMPORT_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "导入批次不存在"),
    DOCUMENT_INDEX_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "知识重建任务不存在"),
    PROJECT_DISABLED(HttpStatus.CONFLICT, "项目已停用"),
    CODE_SNAPSHOT_TYPE_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持该代码快照文件类型"),
    CODE_SNAPSHOT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "代码快照超过资源上限"),
    CODE_SNAPSHOT_ARCHIVE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "代码快照 ZIP 不安全或无法处理"),
    CODE_SNAPSHOT_JOB_ACTIVE(HttpStatus.CONFLICT, "该分支已有活动中的代码快照任务"),
    CODE_SNAPSHOT_NOT_FOUND(HttpStatus.NOT_FOUND, "活动代码快照不存在"),
    CODE_SNAPSHOT_NOT_ACTIVE(HttpStatus.CONFLICT, "代码快照不是当前活动快照"),
    CODE_SNAPSHOT_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "代码快照任务不存在"),
    CODE_FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "代码文件不存在"),
    CODE_SNIPPET_RANGE_INVALID(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "代码片段行范围无效"),
    CODE_INDEX_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "代码索引暂时不可用"),
    KNOWLEDGE_INDEX_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "知识索引暂时不可用"),
    KNOWLEDGE_EMBEDDING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "知识语义模型暂时不可用"),
    QA_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "问答记录不存在"),
    AGENT_RUN_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "相同幂等键对应的问答输入不一致"),
    AGENT_SKILL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "问答能力暂时不可用"),
    AGENT_RUNTIME_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "问答运行时暂时不可用"),
    AGENT_RUNTIME_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "问答运行时繁忙"),
    AGENT_MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "问答模型暂时不可用"),
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
