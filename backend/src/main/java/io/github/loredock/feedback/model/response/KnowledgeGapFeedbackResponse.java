package io.github.loredock.feedback.model.response;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.feedback.model.enums.KnowledgeGapStatus;
import io.github.loredock.feedback.model.enums.KnowledgeGapType;
import java.time.Instant;
import java.util.List;

/** 不含幂等摘要、证据正文和服务器路径的知识缺口公开响应。 */
public record KnowledgeGapFeedbackResponse(
        Long feedbackId,
        String projectIdentifier,
        String branch,
        KnowledgeGapType type,
        KnowledgeGapStatus status,
        String question,
        String note,
        Long questionId,
        Long runId,
        AgentResultType resultType,
        AgentRefusalReason refusalReason,
        AgentErrorCode errorCode,
        List<Long> citationEvidenceIds,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
