package io.github.loredock.feedback.converter;

import io.github.loredock.feedback.model.response.KnowledgeGapFeedbackResponse;
import io.github.loredock.feedback.model.snapshot.KnowledgeGapFeedbackSnapshot;

/** 显式挑选公开反馈字段，禁止直接序列化领域或持久化实体。 */
public final class KnowledgeGapHttpMapper {
    private KnowledgeGapHttpMapper() {
    }

    public static KnowledgeGapFeedbackResponse toResponse(KnowledgeGapFeedbackSnapshot snapshot) {
        var value = snapshot.feedback();
        return new KnowledgeGapFeedbackResponse(
                value.id(), value.projectIdentifier(), value.branch(), value.type(), value.status(),
                value.question(), value.note(), value.questionId(), value.runId(), value.resultType(),
                value.refusalReason(), value.errorCode(), snapshot.citationEvidenceIds(),
                value.createdAt(), value.updatedAt(), value.createdBy(), value.updatedBy());
    }
}
