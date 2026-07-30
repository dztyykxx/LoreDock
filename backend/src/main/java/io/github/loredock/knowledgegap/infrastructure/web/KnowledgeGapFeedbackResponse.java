package io.github.loredock.knowledgegap.infrastructure.web;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 不含幂等摘要、证据正文和服务器路径的知识缺口公开响应。 */
public record KnowledgeGapFeedbackResponse(
        UUID feedbackId,
        String projectIdentifier,
        String branch,
        KnowledgeGapType type,
        KnowledgeGapStatus status,
        String question,
        String note,
        UUID questionId,
        UUID runId,
        AgentResultType resultType,
        AgentRefusalReason refusalReason,
        AgentErrorCode errorCode,
        List<UUID> citationEvidenceIds,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
