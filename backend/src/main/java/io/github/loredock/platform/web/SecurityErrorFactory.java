package io.github.loredock.platform.web;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.MDC;

/**
 * 为 MVC 异常与前置安全过滤器创建相同的错误体和 trace ID 语义。
 */
public final class SecurityErrorFactory {

    private final Clock timeProvider;

    /** @param timeProvider UTC 时间端口 */
    public SecurityErrorFactory(Clock timeProvider) {
        this.timeProvider = timeProvider;
    }

    /**
     * @param code 稳定错误码
     * @param fieldErrors 可公开的字段错误
     * @return 不含请求原值和内部诊断的统一错误体
     */
    public ApiError create(ErrorCode code, List<FieldError> fieldErrors) {
        return new ApiError(
                code.name(),
                code.publicMessage(),
                OffsetDateTime.ofInstant(timeProvider.instant(), ZoneOffset.UTC),
                traceId(),
                fieldErrors
        );
    }

    /** @return 当前请求 trace ID；过滤器外调用时返回稳定占位值 */
    public static String traceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_MDC_KEY);
        return traceId == null ? "unavailable" : traceId;
    }
}
