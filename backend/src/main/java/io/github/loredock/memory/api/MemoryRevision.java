package io.github.loredock.memory.api;

import java.time.OffsetDateTime;

/** 记忆某一版本的变更后快照。 */
public record MemoryRevision(
        Long id,
        Long memoryId,
        long revision,
        String snapshot,
        String operation,
        String relation,
        String reason,
        Long sourceRunId,
        Long sourceConversationId,
        Long sourceMessageId,
        String operatorId,
        OffsetDateTime createdAt
) {
}
