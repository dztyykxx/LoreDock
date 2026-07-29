package io.github.loredock.job.application;

/**
 * 非幂等后台任务提交命令；每次成功调用都创建新的任务 ID。
 *
 * @param type 已注册任务类型
 * @param inputObjectKey 可选输入对象键
 */
public record JobRequest(String type, String inputObjectKey) {
}
