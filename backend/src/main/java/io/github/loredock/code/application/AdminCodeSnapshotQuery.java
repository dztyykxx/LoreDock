package io.github.loredock.code.application;

import java.util.UUID;

/**
 * 管理员快照分页筛选。
 *
 * @param projectId 可选项目 UUID
 * @param branchId 可选分支 UUID；指定时必须同时指定项目
 * @param page 零基页码
 * @param size 页容量，默认 20、最大 100
 */
public record AdminCodeSnapshotQuery(UUID projectId, UUID branchId, int page, int size) {

    /** 创建并验证稳定分页及筛选范围。 */
    public AdminCodeSnapshotQuery {
        if (page < 0 || size < 1 || size > 100 || branchId != null && projectId == null) {
            throw new IllegalArgumentException("invalid code snapshot admin query");
        }
    }
}
