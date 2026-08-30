package io.github.loredock.platform.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import io.github.loredock.agent.api.AgentRequestException;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.auth.exception.ForbiddenOperationException;
import io.github.loredock.auth.exception.InvalidCredentialsException;
import io.github.loredock.auth.exception.LoginRequiredException;
import io.github.loredock.knowledge.exception.DocumentReplacementConflictException;
import io.github.loredock.knowledge.exception.DocumentStateConflictException;
import io.github.loredock.knowledge.api.KnowledgeDraftException;
import io.github.loredock.memory.api.MemoryRequestException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 将已知和未预期异常映射为统一安全错误体，同时把经过脱敏的诊断上下文保留在服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final SecurityErrorFactory errorFactory;
    private final SensitiveDataRedactor redactor;

    /**
     * @param timeProvider UTC 时间端口
     * @param redactor 日志诊断脱敏器
     */
    public GlobalExceptionHandler(Clock timeProvider, SensitiveDataRedactor redactor) {
        this.errorFactory = new SecurityErrorFactory(timeProvider);
        this.redactor = redactor;
    }

    /**
     * 映射带稳定语义的应用失败，异常原始消息只以脱敏形式写日志。
     *
     * @param exception 应用异常
     * @return 统一错误响应
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiError> handleApplicationException(ApplicationException exception) {
        ErrorCode code = exception.errorCode();
        LOGGER.warn("application_failure traceId={} code={} diagnostic={}",
                traceId(), code.name(), redactor.redact(exception.getMessage()));
        return response(code, List.of());
    }

    /**
     * 把 Agent 启动阶段的稳定错误限制到 Web 允许公开的状态；未知运行时错误继续按通用内部失败处理。
     *
     * @param exception Agent 受理前失败
     * @return 幂等冲突或运行时不可用错误
     */
    @ExceptionHandler(AgentRequestException.class)
    public ResponseEntity<ApiError> handleAgentRequest(AgentRequestException exception) {
        ErrorCode code = switch (exception.errorCode()) {
            case AGENT_RUN_IDEMPOTENCY_CONFLICT -> ErrorCode.AGENT_RUN_IDEMPOTENCY_CONFLICT;
            case AGENT_SKILL_UNAVAILABLE -> ErrorCode.AGENT_SKILL_UNAVAILABLE;
            case AGENT_RUNTIME_UNAVAILABLE, AGENT_DISABLED -> ErrorCode.AGENT_RUNTIME_UNAVAILABLE;
            case AGENT_RUNTIME_BUSY -> ErrorCode.AGENT_RUNTIME_BUSY;
            case AGENT_MODEL_UNAVAILABLE -> ErrorCode.AGENT_MODEL_UNAVAILABLE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        LOGGER.warn("agent_request_failure traceId={} code={}", traceId(), code.name());
        return response(code, List.of());
    }

    /** 把知识任务与草稿的稳定失败语义映射为公开 404/409，不透传内部异常文本。 */
    @ExceptionHandler({KnowledgeTaskRequestException.class, KnowledgeDraftException.class})
    public ResponseEntity<ApiError> handleKnowledgeTask(RuntimeException exception) {
        ErrorCode code;
        if (exception instanceof KnowledgeTaskRequestException task) {
            code = switch (task.code()) {
                case KNOWLEDGE_TASK_NOT_FOUND -> ErrorCode.KNOWLEDGE_TASK_NOT_FOUND;
                case AGENT_DEFINITION_INVALID -> ErrorCode.KNOWLEDGE_TASK_DEFINITION_UNAVAILABLE;
                default -> ErrorCode.KNOWLEDGE_TASK_STATE_CONFLICT;
            };
        } else {
            KnowledgeDraftException draft = (KnowledgeDraftException) exception;
            code = draft.code() == KnowledgeDraftException.Code.DRAFT_NOT_FOUND
                    ? ErrorCode.KNOWLEDGE_DRAFT_NOT_FOUND : ErrorCode.KNOWLEDGE_DRAFT_CONFLICT;
        }
        LOGGER.warn("knowledge_task_failure traceId={} code={}", traceId(), code.name());
        return response(code, List.of());
    }

    /** 把记忆业务的稳定失败语义映射为公开 400/404/409/503，不透传内部异常文本。 */
    @ExceptionHandler(MemoryRequestException.class)
    public ResponseEntity<ApiError> handleMemoryRequest(MemoryRequestException exception) {
        ErrorCode code = switch (exception.code()) {
            case MEMORY_NOT_FOUND -> ErrorCode.MEMORY_NOT_FOUND;
            case MEMORY_SCOPE_VIOLATION -> ErrorCode.MEMORY_SCOPE_VIOLATION;
            case MEMORY_PROJECT_INVALID -> ErrorCode.MEMORY_PROJECT_INVALID;
            case MEMORY_FIELD_INVALID -> ErrorCode.MEMORY_FIELD_INVALID;
            case MEMORY_SCOPE_EDIT_FORBIDDEN -> ErrorCode.MEMORY_SCOPE_EDIT_FORBIDDEN;
            case MEMORY_BUDGET_EXCEEDED -> ErrorCode.MEMORY_BUDGET_EXCEEDED;
            case MEMORY_JUDGE_UNAVAILABLE -> ErrorCode.MEMORY_JUDGE_UNAVAILABLE;
        };
        LOGGER.warn("memory_failure traceId={} code={}", traceId(), code.name());
        return response(code, List.of());
    }

    /**
     * 映射知识领域状态冲突；领域层保持纯 Java，因此在平台 HTTP 边界补充稳定错误码。
     *
     * @param exception 状态机或替代规则冲突
     * @return 对应 DOCUMENT_STATE_CONFLICT 或 DOCUMENT_REPLACEMENT_CONFLICT 的 409 响应
     */
    @ExceptionHandler({DocumentStateConflictException.class, DocumentReplacementConflictException.class})
    public ResponseEntity<ApiError> handleKnowledgeConflict(RuntimeException exception) {
        ErrorCode code = exception instanceof DocumentReplacementConflictException
                ? ErrorCode.DOCUMENT_REPLACEMENT_CONFLICT
                : ErrorCode.DOCUMENT_STATE_CONFLICT;
        LOGGER.warn("knowledge_conflict traceId={} code={} classification=domain_conflict", traceId(), code.name());
        return response(code, List.of());
    }

    /**
     * 统一映射错误账号与错误密码，不记录异常对象以避免未来误携带提交凭据。
     *
     * @param exception 固定凭据失败
     * @return 统一 401 错误
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception) {
        return identityError(ErrorCode.AUTH_INVALID_CREDENTIALS, "invalid_credentials");
    }

    /**
     * 映射应用与 Sa-Token 的未登录语义；Cookie 和 Token 值不会写入日志。
     *
     * @param exception 无有效 Web 会话
     * @return 统一 401 错误
     */
    @ExceptionHandler({LoginRequiredException.class, NotLoginException.class})
    public ResponseEntity<ApiError> handleLoginRequired(Exception exception) {
        return identityError(ErrorCode.AUTH_LOGIN_REQUIRED, "login_required");
    }

    /**
     * 映射已登录但角色或权限不足，明确与未登录 401 区分。
     *
     * @param exception 服务端授权拒绝
     * @return 统一 403 错误
     */
    @ExceptionHandler({ForbiddenOperationException.class, NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<ApiError> handleForbidden(Exception exception) {
        return identityError(ErrorCode.AUTH_FORBIDDEN, "forbidden");
    }

    /**
     * 汇总请求字段错误，只返回字段路径和约束原因码，不回显拒绝值。
     *
     * @param exception Bean Validation 失败
     * @return 统一参数错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), reasonCode(error.getCode())))
                .sorted(Comparator.comparing(FieldError::field))
                .toList();
        return response(ErrorCode.INVALID_REQUEST, fields);
    }

    /**
     * 映射 JSON/枚举/Long 解析与领域值对象拒绝，不回显原始请求值或转换异常正文。
     *
     * @param exception 不可安全继续处理的输入
     * @return 统一 400 错误
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleMalformedInput(Exception exception) {
        LOGGER.warn("request_validation_failure traceId={} classification=malformed_input", traceId());
        return response(ErrorCode.INVALID_REQUEST, List.of());
    }

    /**
     * 浏览器关闭 SSE 或下载连接后，Servlet 容器会用该异常通知异步请求已不可写。
     * 连接已经断开，不能再尝试写错误响应，也不应把正常离开页面记录为服务端故障。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedAsyncRequest(AsyncRequestNotUsableException exception) {
        LOGGER.debug("async_client_disconnected traceId={} classification=client_disconnect", traceId());
    }

    /**
     * 未预期异常对外统一隐藏实现信息；为保留排障上下文，堆栈整体脱敏后再记录。
     *
     * @param exception 未分类异常
     * @return 通用内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        LOGGER.error("unexpected_failure traceId={} diagnostic={}", traceId(), redactedStackTrace(exception));
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return response(code, List.of());
    }

    private ApiError error(ErrorCode code, List<FieldError> fieldErrors) {
        return errorFactory.create(code, fieldErrors);
    }

    private ResponseEntity<ApiError> response(ErrorCode code, List<FieldError> fieldErrors) {
        // 错误发生在 SSE 建连前时浏览器只声明接受 text/event-stream；显式 JSON 可避免稳定错误退化为 406。
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(code, fieldErrors));
    }

    private ResponseEntity<ApiError> identityError(ErrorCode code, String classification) {
        LOGGER.warn("identity_failure traceId={} code={} classification={}",
                traceId(), code.name(), classification);
        return response(code, List.of());
    }

    private String reasonCode(String validationCode) {
        if (validationCode == null || validationCode.isBlank()) {
            return "INVALID";
        }
        return validationCode.replaceAll("(?<=[a-z])(?=[A-Z])", "_").toUpperCase(Locale.ROOT);
    }

    private String redactedStackTrace(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return redactor.redact(writer.toString());
    }

    private String traceId() {
        return SecurityErrorFactory.traceId();
    }
}
