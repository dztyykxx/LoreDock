package io.github.loredock.code.api;

/**
 * 跨模块代码搜索输入；快照标识和 commit 只能来自服务端此前返回的活动状态。
 *
 * @param projectIdentifier 项目标识
 * @param branch 实际分支
 * @param query 查询文本
 * @param target 搜索路径、正文或两者
 * @param pathPrefix 可选仓库相对路径前缀
 * @param limit 返回上限
 * @param snapshotId 固定活动快照标识
 * @param commit 固定活动 commit
 */
public record CodeQuery(
        String projectIdentifier,
        String branch,
        String query,
        Target target,
        String pathPrefix,
        int limit,
        Long snapshotId,
        String commit
) {
    public enum Target {
        PATH,
        CONTENT,
        ALL
    }
}
