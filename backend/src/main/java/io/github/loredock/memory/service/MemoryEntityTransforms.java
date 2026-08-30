package io.github.loredock.memory.service;

import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryFull;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemorySourceType;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.model.entity.UserMemoryEntity;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 实体与契约视图之间的映射；枚举存储为字符串，转化失败即为数据损坏。 */
final class MemoryEntityTransforms {

    private MemoryEntityTransforms() {
    }

    /** @return 契约完整视图（正文与管理信息），调用方不得直接暴露实体 */
    static MemoryFull toFull(UserMemoryEntity entity) {
        return new MemoryFull(
                entity.getId(),
                MemoryScope.valueOf(entity.getScopeType()),
                entity.getProjectId(),
                entity.getProjectIdentifier(),
                MemoryCategory.valueOf(entity.getCategory()),
                entity.getTitle(),
                entity.getSummary(),
                entity.getContent(),
                MemoryStatus.valueOf(entity.getStatus()),
                MemorySourceType.valueOf(entity.getSourceType()),
                entity.getSourceRunId(),
                entity.getSourceConversationId(),
                entity.getUseCount() == null ? 0L : entity.getUseCount(),
                utc(entity.getLastUsedAt()),
                utc(entity.getCreatedAt()),
                utc(entity.getUpdatedAt()));
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
