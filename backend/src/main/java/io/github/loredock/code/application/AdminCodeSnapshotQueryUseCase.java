package io.github.loredock.code.application;

import java.util.UUID;

/** 管理员快照与代码任务查询端口，允许查看候选、失败、活动和已替换记录。 */
public interface AdminCodeSnapshotQueryUseCase {

    /**
     * @param query 项目/分支筛选和零基分页；size 最大 100
     * @return 按创建时间倒序、ID 正序稳定排列的页面
     */
    CodeSnapshotAdminPage list(AdminCodeSnapshotQuery query);

    /**
     * 只返回 CODE_SNAPSHOT_BUILD 或 CODE_SNAPSHOT_REINDEX；其他任务类型与未知 ID 统一按不存在失败。
     *
     * @param jobId 代码任务 UUID
     * @return 脱敏代码任务状态
     */
    CodeSnapshotJobView getJob(UUID jobId);
}
