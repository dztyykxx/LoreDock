package io.github.loredock.qa.model.response;

import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;

/**
 * 最近会话侧栏使用的有界安全摘要。
 *
 * @param projectName 项目显示名；GLOBAL 会话为空
 * @param scope GLOBAL 或 PROJECT，前端据此标注"全局"或"项目：名称"
 */
public record WebQaConversationSummaryResponse(
        Long conversationId,
        String projectIdentifier,
        String projectName,
        String scope,
        String title,
        String lastQuestion,
        QaQuestion.Status status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastQuestionAt
) {
}
