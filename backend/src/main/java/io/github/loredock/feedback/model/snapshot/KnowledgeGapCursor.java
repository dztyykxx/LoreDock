package io.github.loredock.feedback.model.snapshot;

import java.time.Instant;

/** 按创建时间与稳定 ID 倒序分页的内部游标事实。 */
public record KnowledgeGapCursor(Instant createdAt, Long id) {
    public KnowledgeGapCursor {
        if (createdAt == null || id == null) {
            throw new IllegalArgumentException("knowledge gap cursor is incomplete");
        }
    }
}
