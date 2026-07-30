package io.github.loredock.code.application;

import java.util.UUID;
import java.util.Optional;

/** 候选快照与 generation 的持久化生命周期端口。 */
public interface CodeSnapshotLifecyclePort {

    /** 在文件系统构建前登记不可查询的 BUILDING generation。 */
    void insertBuilding(UUID generationId, UUID snapshotId, UUID jobId);

    /**
     * 在一个短事务中锁定分支，退休旧活动快照及 generation，并激活已发布候选。
     * 调用方必须先成功关闭、重开验证并原子发布 generation 目录。
     */
    Optional<UUID> activateBuild(CodeSnapshotBuildActivation activation);

    /** 幂等把仍为候选/构建中的记录终结为 FAILED，不得改变已有活动快照。 */
    void failBuild(UUID snapshotId, UUID generationId);

    /** 在短事务中退休同一活动快照的旧 generation，并激活已发布重建 generation。 */
    Optional<UUID> activateReindex(CodeSnapshotReindexActivation activation);

    /** 幂等终结重建 generation；活动快照和旧 generation 必须保持不变。 */
    void failReindex(UUID snapshotId, UUID generationId);
}
