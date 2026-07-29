package io.github.loredock.platform.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import io.github.loredock.identity.application.ForbiddenOperationException;
import io.github.loredock.identity.application.InvalidCredentialsException;
import io.github.loredock.identity.application.LoginRequiredException;
import io.github.loredock.platform.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
    public GlobalExceptionHandler(TimeProvider timeProvider, SensitiveDataRedactor redactor) {
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
        return ResponseEntity.status(code.status()).body(error(code, List.of()));
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
        return ResponseEntity.badRequest().body(error(ErrorCode.INVALID_REQUEST, fields));
    }

    /**
     * 映射 JSON/枚举/UUID 解析与领域值对象拒绝，不回显原始请求值或转换异常正文。
     *
     * @param exception 不可安全继续处理的输入
     * @return 统一 400 错误
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleMalformedInput(Exception exception) {
        LOGGER.warn("request_validation_failure traceId={} classification=malformed_input", traceId());
        return ResponseEntity.badRequest().body(error(ErrorCode.INVALID_REQUEST, List.of()));
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
        return ResponseEntity.status(code.status()).body(error(code, List.of()));
    }

    private ApiError error(ErrorCode code, List<FieldError> fieldErrors) {
        return errorFactory.create(code, fieldErrors);
    }

    private ResponseEntity<ApiError> identityError(ErrorCode code, String classification) {
        LOGGER.warn("identity_failure traceId={} code={} classification={}",
                traceId(), code.name(), classification);
        return ResponseEntity.status(code.status()).body(error(code, List.of()));
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
