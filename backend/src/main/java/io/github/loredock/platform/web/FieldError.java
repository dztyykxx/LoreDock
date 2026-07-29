package io.github.loredock.platform.web;

/**
 * 可定位但不回显原始输入的字段校验错误。
 *
 * @param field 字段路径
 * @param reason 稳定校验原因码
 */
public record FieldError(String field, String reason) {
}
