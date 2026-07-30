package io.github.loredock.knowledgegap.domain;

import java.time.Instant;
import java.util.UUID;

/** 按创建时间与稳定 ID 倒序分页的内部游标事实。 */
public record KnowledgeGapCursor(Instant createdAt, UUID id) {
    public KnowledgeGapCursor {
        if (createdAt == null || id == null) {
            throw new IllegalArgumentException("knowledge gap cursor is incomplete");
        }
    }
}
