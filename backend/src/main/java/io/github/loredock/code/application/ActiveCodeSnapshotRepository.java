package io.github.loredock.code.application;

import java.util.Optional;
import java.util.UUID;

/** 只读取数据库中同时为 ACTIVE 的快照与 generation，候选和失败记录不可见。 */
public interface ActiveCodeSnapshotRepository {
    /** @return 指定分支一次读取固定的活动描述符，无成功快照时为空。 */
    Optional<ActiveCodeSnapshotDescriptor> findActive(UUID branchId);
}
