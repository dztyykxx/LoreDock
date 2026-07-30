package io.github.loredock.knowledgegap.infrastructure.web;

import io.github.loredock.knowledgegap.application.KnowledgeGapFeedbackSnapshot;

/** 显式挑选公开反馈字段，禁止直接序列化领域或持久化实体。 */
final class KnowledgeGapHttpMapper {
    private KnowledgeGapHttpMapper() {
    }

    static KnowledgeGapFeedbackResponse toResponse(KnowledgeGapFeedbackSnapshot snapshot) {
        var value = snapshot.feedback();
        return new KnowledgeGapFeedbackResponse(
                value.id(), value.projectIdentifier(), value.branch(), value.type(), value.status(),
                value.question(), value.note(), value.questionId(), value.runId(), value.resultType(),
                value.refusalReason(), value.errorCode(), snapshot.citationEvidenceIds(),
                value.createdAt(), value.updatedAt(), value.createdBy(), value.updatedBy());
    }
}
