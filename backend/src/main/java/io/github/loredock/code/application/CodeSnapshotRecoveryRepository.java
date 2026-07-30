package io.github.loredock.code.application;

import java.util.Set;
import java.util.UUID;

/** 进程中断后协调候选、BUILDING generation 和任务终态的持久化恢复端口。 */
public interface CodeSnapshotRecoveryRepository {

    /**
     * 把关联任务已终结且未成功激活的 BUILDING generation 及候选快照标记 FAILED。
     *
     * @return 数据库当前全部活动 generation ID，文件清理必须无条件保留这些目录
     */
    Set<UUID> reconcileInterruptedBuilds();
}
