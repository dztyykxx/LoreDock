package io.github.loredock.qa.model.response;

import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;

/** 经服务端持久化的公开用户或助手消息。 */
public record WebQaMessageResponse(
        Long id,
        QaQuestion.MessageRole role,
        String content,
        QaQuestion.ResultType resultType,
        QaQuestion.RefusalReason refusalReason,
        Instant createdAt
) {
}
