package io.github.loredock.platform.audit;

import java.time.Instant;

/**
 * 可持久化业务记录共用的审计值。
 *
 * @param createdAt 创建 UTC 时刻
 * @param updatedAt 最近更新 UTC 时刻
 * @param createdBy 创建者
 * @param updatedBy 最近更新者
 */
public record AuditMetadata(
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
