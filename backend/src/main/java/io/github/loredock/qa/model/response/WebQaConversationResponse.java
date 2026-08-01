package io.github.loredock.qa.model.response;

import java.util.List;

/** 单个会话及其稳定正序轮次响应。 */
public record WebQaConversationResponse(
        WebQaConversationSummaryResponse conversation,
        List<WebQaQuestionResponse> rounds
) {
    /** 复制轮次列表，保持详情快照不可变。 */
    public WebQaConversationResponse {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }
}
