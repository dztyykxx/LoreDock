package io.github.loredock.qa.model.result;

import java.time.Instant;

/** QA 会话仓储边界内的归属和列表排序事实。 */
public record WebQaConversationRecord(
        Long id,
        String operatorId,
        Long projectId,
        String projectIdentifier,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Instant lastQuestionAt
) {
}
