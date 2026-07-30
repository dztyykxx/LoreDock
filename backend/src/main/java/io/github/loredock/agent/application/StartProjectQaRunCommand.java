package io.github.loredock.agent.application;

/**
 * project_qa 启动输入。问题限制为去除首尾空白后 1～2000 个 Unicode 字符；分支为空时使用 main。
 *
 * @param idempotencyKey 调用方在当前操作者内唯一的幂等键
 * @param operatorId 已认证操作者稳定标识
 * @param operatorRole ADMIN 或 MEMBER
 * @param projectIdentifier 已启用项目业务标识
 * @param branch 可选分支
 * @param question 本次问题；只在调度所需的短生命周期内保留原文
 */
public record StartProjectQaRunCommand(
        String idempotencyKey,
        String operatorId,
        String operatorRole,
        String projectIdentifier,
        String branch,
        String question
) {
}
