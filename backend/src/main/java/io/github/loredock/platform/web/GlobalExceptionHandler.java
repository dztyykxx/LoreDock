package io.github.loredock.platform.web;

import io.github.loredock.platform.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 将已知和未预期异常映射为统一安全错误体，同时把经过脱敏的诊断上下文保留在服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final TimeProvider timeProvider;
    private final SensitiveDataRedactor redactor;

    /**
     * @param timeProvider UTC 时间端口
     * @param redactor 日志诊断脱敏器
     */
    public GlobalExceptionHandler(TimeProvider timeProvider, SensitiveDataRedactor redactor) {
        this.timeProvider = timeProvider;
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
        return new ApiError(
                code.name(),
                code.publicMessage(),
                OffsetDateTime.ofInstant(timeProvider.now(), ZoneOffset.UTC),
                traceId(),
                fieldErrors
        );
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
        String traceId = MDC.get(TraceIdFilter.TRACE_MDC_KEY);
        return traceId == null ? "unavailable" : traceId;
    }
}
