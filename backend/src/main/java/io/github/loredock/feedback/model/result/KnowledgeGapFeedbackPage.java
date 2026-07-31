package io.github.loredock.feedback.model.result;

import io.github.loredock.feedback.model.snapshot.KnowledgeGapFeedbackSnapshot;
import java.util.List;

/** 管理端有界游标分页结果。 */
public record KnowledgeGapFeedbackPage(List<KnowledgeGapFeedbackSnapshot> items, String nextCursor) {
    public KnowledgeGapFeedbackPage {
        items = List.copyOf(items);
    }
}
