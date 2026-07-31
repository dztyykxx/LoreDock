package io.github.loredock.platform.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 所有 HTTP API 失败共用的安全响应契约。
 *
 * @param code 稳定错误码
 * @param message 面向调用方的安全消息
 * @param timestamp UTC 时间戳
 * @param traceId 日志关联标识
 * @param fieldErrors 可安全公开的字段校验错误
 */
public record ApiError(
        String code,
        String message,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime timestamp,
        String traceId,
        List<FieldError> fieldErrors
) {
}
