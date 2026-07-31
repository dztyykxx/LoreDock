package io.github.loredock.feedback.model.snapshot;

import io.github.loredock.feedback.model.result.KnowledgeGapFeedbackRecord;
import java.util.List;

/** 管理与创建接口共享的有限反馈快照，不包含证据正文。 */
public record KnowledgeGapFeedbackSnapshot(
        KnowledgeGapFeedbackRecord feedback,
        List<Long> citationEvidenceIds
) {
    public KnowledgeGapFeedbackSnapshot {
        citationEvidenceIds = citationEvidenceIds == null ? List.of() : List.copyOf(citationEvidenceIds);
    }
}
