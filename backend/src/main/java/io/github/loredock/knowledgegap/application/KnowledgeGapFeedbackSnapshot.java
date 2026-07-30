package io.github.loredock.knowledgegap.application;

import java.util.List;
import java.util.UUID;

/** 管理与创建接口共享的有限反馈快照，不包含证据正文。 */
public record KnowledgeGapFeedbackSnapshot(
        KnowledgeGapFeedbackRecord feedback,
        List<UUID> citationEvidenceIds
) {
    public KnowledgeGapFeedbackSnapshot {
        citationEvidenceIds = citationEvidenceIds == null ? List.of() : List.copyOf(citationEvidenceIds);
    }
}
