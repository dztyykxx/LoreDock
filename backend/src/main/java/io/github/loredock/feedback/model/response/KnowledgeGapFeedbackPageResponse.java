package io.github.loredock.feedback.model.response;

import java.util.List;

/** 管理端知识缺口游标页。 */
public record KnowledgeGapFeedbackPageResponse(
        List<KnowledgeGapFeedbackResponse> items,
        String nextCursor
) {
}
