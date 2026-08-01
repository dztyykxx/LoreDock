package io.github.loredock.qa.model.response;

import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;

/** 最近会话侧栏使用的有界安全摘要。 */
public record WebQaConversationSummaryResponse(
        Long conversationId,
        String projectIdentifier,
        String title,
        String lastQuestion,
        QaQuestion.Status status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastQuestionAt
) {
}
