package io.github.loredock.job.model.request;


/**
 * 非幂等后台任务提交命令；每次成功调用都创建新的任务 ID。
 *
 * @param type 已注册任务类型
 * @param inputObjectKey 可选输入对象键
 * @param projectId 可选项目范围
 * @param branchId 可选分支范围
 * @param snapshotId 可选代码快照范围
 */
public record JobRequest(
        String type,
        String inputObjectKey,
        Long projectId,
        Long branchId,
        Long snapshotId
) {
    /** 保留不需要项目范围的既有知识与平台任务提交契约。 */
    public JobRequest(String type, String inputObjectKey) {
        this(type, inputObjectKey, null, null, null);
    }
}
