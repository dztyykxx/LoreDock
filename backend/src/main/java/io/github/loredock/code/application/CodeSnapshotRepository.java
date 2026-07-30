package io.github.loredock.code.application;

import java.util.Optional;
import java.util.UUID;

/** 代码快照持久化端口；表结构只由 Flyway 管理。 */
public interface CodeSnapshotRepository {

    /** 在调用方事务中插入尚不可查询的 CANDIDATE。 */
    void insertCandidate(CodeSnapshotRecord snapshot);

    /** @return 包含候选和历史记录的稳定管理分页。 */
    CodeSnapshotAdminPage listAdmin(AdminCodeSnapshotQuery query);

    /** @return 指定快照内部记录；对象键不得越过应用层进入 HTTP 响应。 */
    Optional<CodeSnapshotRecord> findById(UUID snapshotId);
}
