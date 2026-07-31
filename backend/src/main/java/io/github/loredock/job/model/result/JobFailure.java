package io.github.loredock.job.model.result;

/**
 * 可持久化的任务失败信息。
 *
 * @param code 稳定错误码
 * @param message 脱敏且限长的诊断摘要
 */
public record JobFailure(String code, String message) {
}
