package io.github.loredock.project.model.response;

import java.time.Instant;

/**
 * 分支 HTTP 响应，不返回可被误用为文件路径的服务端位置。
 *
 * @param id 分支 Long
 * @param name 分支名
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @param createdBy 创建操作者
 * @param updatedBy 更新操作者
 */
public record BranchResponse(
        Long id,
        String name,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
